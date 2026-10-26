package net.ximatai.muyun.spring.starter.configuration.filetransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.attachment.FileTransferClient;
import net.ximatai.muyun.spring.platform.attachment.FileTransferFileMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MuYunFileServerTransferClientTest {
    @AfterEach
    void clearCurrentUser() {
        CurrentUserContext.clear();
    }

    @Test
    void officialAssemblyNeverForwardsTicketsOnRedirectsOrLeaksUpstreamErrors() throws Exception {
        var destinationRequests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var status = new java.util.concurrent.atomic.AtomicInteger(307);
        server.createContext("/api/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (status.get() == 307) {
                exchange.getResponseHeaders().set("Location", base + "/untrusted?" + exchange.getRequestURI().getRawQuery());
            }
            byte[] body = "sensitive-upstream-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/untrusted", exchange -> {
            destinationRequests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close();
        });
        server.start();
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("u", "u", "tenant"))) {
            var properties = new MuYunFileServerTransferProperties();
            properties.setBaseUrl(URI.create(base)); properties.setIssuer("test"); properties.setSecret("server-secret");
            var access = new MuYunFileServerTransferAccessService(properties, new ObjectMapper());
            var client = new MuYunFileServerTransferConfiguration().fileTransferClient(access, new ObjectMapper());
            for (int code : new int[]{307, 500}) {
                status.set(code);
                for (Runnable request : java.util.List.<Runnable>of(() -> client.readMetadata("file"),
                        () -> client.promote("file"), () -> client.delete("file"), () -> client.openContent("file"))) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(request::run)
                            .hasNoCause().hasMessageNotContaining("access_token").hasMessageNotContaining("sensitive-upstream-body");
                }
            }
            assertThat(destinationRequests).hasValue(0);
        } finally { server.stop(0); }
    }

    @Test
    void shouldReadAndPromoteMetadataThroughTheOfficialProviderContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FileTransferClient client = new MuYunFileServerTransferClient(accessService(), builder.build(), new ObjectMapper());
        String fileId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        String metadata = """
                {"data":{"id":"%s","originalFilename":"manual.pdf","extension":"pdf",
                "mimeType":"application/pdf","sizeBytes":1536,"sha256":"hash","status":"ACTIVE",
                "temporary":true,"uploadedAt":"2026-08-08T00:00:00Z"}}
                """.formatted(fileId);
        String promoted = """
                {"data":{"items":[{"id":"%s","originalFilename":"manual.pdf","extension":"pdf",
                "mimeType":"application/pdf","sizeBytes":1536,"sha256":"hash","status":"ACTIVE",
                "temporary":false,"uploadedAt":"2026-08-08T00:00:00Z"}]}}
                """.formatted(fileId);
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/" + fileId + "?access_token=")))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(metadata, org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/" + fileId + "/promote?access_token=")))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(promoted, org.springframework.http.MediaType.APPLICATION_JSON));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "operator", "tenant-a"))) {
            FileTransferFileMetadata read = client.readMetadata(fileId);
            FileTransferFileMetadata confirmed = client.promote(fileId);
            assertThat(read.temporary()).isTrue();
            assertThat(confirmed.temporary()).isFalse();
            assertThat(confirmed.sizeBytes()).isEqualTo(1536);
        }
        server.verify();
    }

    @Test
    void readsDimensionsAndRejectsMalformedProviderFacts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FileTransferClient client = new MuYunFileServerTransferClient(accessService(), builder.build(), new ObjectMapper());
        String fileId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        for (String dimensions : java.util.List.of("16,8", "16,null", "-1,8", "2147483648,8", "16.5,8")) {
            String[] pair = dimensions.split(",");
            String json = """
                    {"data":{"id":"%s","mimeType":"image/png","sizeBytes":100,"temporary":true,
                    "imageWidth":%s,"imageHeight":%s}}
                    """.formatted(fileId, pair[0], pair[1]);
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/" + fileId + "?access_token=")))
                    .andRespond(withSuccess(json, org.springframework.http.MediaType.APPLICATION_JSON));
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "operator", "tenant-a"))) {
            var metadata = client.readMetadata(fileId);
            assertThat(metadata.imageWidth()).isEqualTo(16);
            assertThat(metadata.imageHeight()).isEqualTo(8);
            for (int i = 0; i < 4; i++) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.readMetadata(fileId))
                        .hasMessageContaining("image dimension");
            }
        }
        server.verify();
    }

    @Test
    void rejectsMissingOrNonBooleanTemporaryStateForMetadataAndPromotion() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FileTransferClient client = new MuYunFileServerTransferClient(accessService(), builder.build(), new ObjectMapper());
        String fileId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        var invalidStates = java.util.List.of("", ",\"temporary\":null", ",\"temporary\":\"true\"",
                ",\"temporary\":1", ",\"temporary\":{}", ",\"temporary\":[]");
        for (String state : invalidStates) {
            String metadata = "{\"id\":\"" + fileId + "\",\"sizeBytes\":100,\"status\":\"ACTIVE\"" + state + "}";
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/" + fileId + "?access_token=")))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"data\":" + metadata + "}", org.springframework.http.MediaType.APPLICATION_JSON));
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/" + fileId + "/promote?access_token=")))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess("{\"data\":{\"items\":[" + metadata + "]}}", org.springframework.http.MediaType.APPLICATION_JSON));
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "operator", "tenant-a"))) {
            for (String state : invalidStates) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.readMetadata(fileId))
                        .hasMessageContaining("no reliable temporary state");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.promote(fileId))
                        .hasMessageContaining("no reliable temporary state");
            }
        }
        server.verify();
    }

    @Test
    void readsAuthoritativeUploadIdentityAndTemporaryStateInOneMetadataRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FileTransferClient client = new MuYunFileServerTransferClient(accessService(), builder.build(), new ObjectMapper());
        String fileId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/" + fileId + "?access_token=")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"data":{"id":"%s","sizeBytes":100,"temporary":true,"status":"ACTIVE",
                        "uploadedBy":"actual-uploader","tenantId":"tenant-a"}}
                        """.formatted(fileId), org.springframework.http.MediaType.APPLICATION_JSON));
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "tenant-a"))) {
            var facts = client.readUploadFacts(fileId);
            assertThat(facts.uploaderUserId()).isEqualTo("actual-uploader");
            assertThat(facts.tenantId()).isEqualTo("tenant-a");
            assertThat(facts.metadata().fileId()).isEqualTo(fileId);
            assertThat(facts.metadata().temporary()).isTrue();
        }
        server.verify();
    }

    @Test
    void refusesUploadFactsWithoutTextualNonBlankAuthoritativeUploaderAndTenant() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FileTransferClient client = new MuYunFileServerTransferClient(accessService(), builder.build(), new ObjectMapper());
        String fileId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        var invalidValues = java.util.List.of("missing", "null", "42", "true", "{}", "[]", "\"\"", "\" \"");
        for (String field : java.util.List.of("uploadedBy", "tenantId")) {
            for (String value : invalidValues) {
                var body = new java.util.LinkedHashMap<String, Object>();
                body.put("id", fileId); body.put("sizeBytes", 100); body.put("temporary", true);
                body.put("uploadedBy", "actual-uploader"); body.put("tenantId", "tenant-a");
                if (value.equals("missing")) body.remove(field);
                else {
                    try { body.put(field, new ObjectMapper().readValue(value, Object.class)); }
                    catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
                }
                String json;
                try { json = new ObjectMapper().writeValueAsString(java.util.Map.of("data", body)); }
                catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
                server.expect(requestTo(org.hamcrest.Matchers.containsString("/" + fileId + "?access_token=")))
                        .andExpect(method(HttpMethod.GET))
                        .andRespond(withSuccess(json, org.springframework.http.MediaType.APPLICATION_JSON));
            }
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "tenant-a"))) {
            for (int i = 0; i < invalidValues.size() * 2; i++) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.readUploadFacts(fileId))
                        .hasMessageContaining("no authoritative upload identity");
            }
        }
        server.verify();
    }

    @Test
    void shouldDeleteThroughAPurposeScopedServerCredential() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FileTransferClient client = new MuYunFileServerTransferClient(accessService(), builder.build(), new ObjectMapper());
        String fileId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/" + fileId + "?access_token=")))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"data\":{\"fileId\":\"" + fileId + "\",\"status\":\"DELETED\"}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "operator", "tenant-a"))) {
            client.delete(fileId);
        }
        server.verify();
    }

    private MuYunFileServerTransferAccessService accessService() {
        MuYunFileServerTransferProperties properties = new MuYunFileServerTransferProperties();
        properties.setBaseUrl(URI.create("http://files.example"));
        properties.setIssuer("muyun-platform");
        properties.setSecret("test-signing-secret");
        return new MuYunFileServerTransferAccessService(properties, new ObjectMapper());
    }
}
