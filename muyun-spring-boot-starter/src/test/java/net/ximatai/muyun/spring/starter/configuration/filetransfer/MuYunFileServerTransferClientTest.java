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
