package net.ximatai.muyun.spring.starter.configuration.filetransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.attachment.FileReceptionClient;
import net.ximatai.muyun.spring.platform.attachment.FileReceptionException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static net.ximatai.muyun.spring.platform.attachment.FileReceptionException.Kind.*;

class MuYunFileServerReceptionClientTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private final ObjectMapper json = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private MuYunFileServerTransferProperties properties;
    private volatile String response;
    private volatile int status = 200;
    private volatile boolean stalled;
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    private final AtomicInteger redirects = new AtomicInteger();
    @TempDir Path directory;
    record Request(String method, String path, String tenant, String user, String bearer, byte[] body) { }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        properties = new MuYunFileServerTransferProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setIssuer("test"); properties.setSecret("test-public-ticket-key");
        properties.getReception().setServiceToken("private-reception-service-token");
        properties.getReception().setRequestTimeout(Duration.ofSeconds(3));
        server.createContext("/api/v1/internal/receptions", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Tenant-Id"), exchange.getRequestHeaders().getFirst("X-User-Id"),
                    exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestBody().readAllBytes()));
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (status == 307) exchange.getResponseHeaders().set("Location", properties.getBaseUrl() + "/leak");
            exchange.sendResponseHeaders(status, body.length);
            try {
                if (stalled) releaseBody.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/leak", exchange -> { redirects.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.start();
        response = snapshot("OPEN", Map.of());
    }
    @AfterEach void stop() {
        releaseBody.countDown(); server.stop(0); executor.shutdownNow(); CurrentUserContext.clear(); TenantContext.clear();
    }
    private FileReceptionClient client() { return new MuYunFileServerTransferConfiguration().fileReceptionClient(properties, json); }
    private FileReceptionClient.Create request() { return new FileReceptionClient.Create("persisted_key", 2, 100, 60); }
    private String snapshot(String state, Map<String,String> assets) throws Exception {
        return json.writeValueAsString(Map.of("data", Map.of("id", ID, "state", state, "bucket", "receiving-bucket",
                "prefix", "receiving/" + ID.replace("-", "") + "/", "expiresAt", 1700000000000L,
                "maxObjects", 2, "maxBytes", 100, "assets", assets)));
    }

    @Test void coversProtocolWithoutOwningBusinessRetriesOrIdentity() throws Exception {
        var client = client();
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("user-a", "A", "tenant-a"))) {
            assertThat(client.create(request()).state()).isEqualTo(FileReceptionClient.State.OPEN);
            assertThat(client.get(ID).id()).isEqualTo(ID);
            response = "{\"data\":[{\"key\":\"folder/photo.jpg\",\"sizeBytes\":3,\"etag\":\"opaque\"}]}";
            assertThat(client.objects(ID)).containsExactly(new FileReceptionClient.StagedObject("folder/photo.jpg", 3));
            response = "{\"data\":{\"accepted\":true}}";
            Path content = Files.write(directory.resolve("photo"), new byte[]{1,2,3});
            client.put(ID, "folder/photo.jpg", content);
            assertThat(Files.readAllBytes(content)).containsExactly(1,2,3);
            response = snapshot("READY", Map.of("folder/photo.jpg", "file_1"));
            assertThat(client.confirm(ID, List.of(new FileReceptionClient.ObjectEntry("folder/photo.jpg", "照片.jpg", 3, null)))
                    .assets()).containsEntry("folder/photo.jpg", "file_1");
            response = snapshot("CANCELLED", Map.of());
            assertThat(client.cancel(ID).retiredWithoutAssets()).isTrue();
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("user-b", "B", "tenant-b"))) { client.get(ID); }
        assertThat(requests).hasSize(7);
        assertThat(requests.subList(0,6)).allSatisfy(r -> {
            assertThat(r.tenant()).isEqualTo("tenant-a"); assertThat(r.user()).isEqualTo("user-a");
            assertThat(r.bearer()).isEqualTo("Bearer private-reception-service-token");
        });
        assertThat(requests.getLast().tenant()).isEqualTo("tenant-b");
        assertThat(json.readTree(requests.getFirst().body()).path("idempotencyKey").asText()).isEqualTo("persisted_key");
        assertThat(requests.get(3).method()).isEqualTo("PUT");
        assertThat(requests.get(3).body()).containsExactly(1,2,3);
        assertThat(json.readTree(requests.get(4).body()).path("objects").get(0).path("filename").asText()).isEqualTo("照片.jpg");
    }

    @Test void rejectsMissingIdentityAndUnsafeInputsBeforeSending() {
        var client = client();
        assertThatThrownBy(() -> client.create(request())).isInstanceOf(IllegalStateException.class);
        try (var ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            assertThatThrownBy(() -> client.create(request())).isInstanceOf(IllegalStateException.class);
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("user", "User", "tenant"))) {
            for (String invalid : List.of("../x", "%2f", "id?secret", "11111111-1111-1111-1111-11111111111x"))
                assertThatThrownBy(() -> client.get(invalid)).isInstanceOf(IllegalArgumentException.class);
            for (String invalid : List.of("../x", "a/../b", "/x", "a%2fb", "a?b"))
                assertThatThrownBy(() -> client.put(ID, invalid, directory)).isInstanceOf(IllegalArgumentException.class);
            var entry = new FileReceptionClient.ObjectEntry("x", "x", 0, null);
            assertThatThrownBy(() -> client.confirm(ID, List.of(entry,entry))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test void explicitSystemNamespaceAndConfigurationFailClosed() {
        properties.setSystemScopeId("system-files");
        try (var ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            client().get(ID);
            assertThat(requests.getFirst().tenant()).isEqualTo("system-files");
        }
        for (String url : List.of("file:///tmp", "http://u:secret@localhost", "http://localhost?secret", "http://localhost#secret")) {
            properties.setBaseUrl(URI.create(url));
            assertThatThrownBy(this::client).hasMessage("invalid file reception deployment configuration");
        }
    }

    @Test void receptionAndTransferShareAuthorizedTenantScopeWithoutChangingActor() throws Exception {
        properties.setSystemScopeId("system-files");
        var client = client();
        var access = new MuYunFileServerTransferAccessService(properties, json);
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            try (var tenant = TenantContext.use("tenant-a")) {
                client.create(request());
                assertThat(transferScope(access)).isEqualTo("tenant-a");
                try (var nested = TenantContext.use("tenant-b")) {
                    client.get(ID);
                    assertThat(transferScope(access)).isEqualTo("tenant-b");
                }
                client.get(ID);
                assertThat(transferScope(access)).isEqualTo("tenant-a");
            }
            try (var system = TenantContext.system("system files")) {
                client.get(ID);
                assertThat(transferScope(access)).isEqualTo("system-files");
            }
        }
        assertThat(requests).extracting(Request::tenant)
                .containsExactly("tenant-a", "tenant-b", "tenant-a", "system-files");
        assertThat(requests).allSatisfy(r -> assertThat(r.user()).isEqualTo("admin"));
    }

    @Test void tenantScopedSystemActorDoesNotRequireSystemStorageConfiguration() throws Exception {
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"));
             var tenant = TenantContext.tenantSystem("tenant-a", "file-reception")) {
            client().get(ID);
            assertThat(requests.getFirst().tenant()).isEqualTo("tenant-a");
            assertThat(transferScope(new MuYunFileServerTransferAccessService(properties, json))).isEqualTo("tenant-a");
        }
    }

    @Test void tenantContextCannotGrantAnonymousOrCrossTenantFileAccess() {
        var client = client();
        var access = new MuYunFileServerTransferAccessService(properties, json);
        try (var tenant = TenantContext.use("tenant-b")) {
            assertThatThrownBy(() -> client.get(ID)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(access::issueUploadAccess).hasMessageContaining("authenticated");
            try (var actor = CurrentUserContext.use(CurrentUser.tenantUser("user", "User", "tenant-a"));
                 var bypass = TenantContext.bypassTenantFilter("trusted query only")) {
                assertThatThrownBy(() -> client.get(ID)).isInstanceOf(PlatformAccessDeniedException.class);
                assertThatThrownBy(access::issueUploadAccess).isInstanceOf(PlatformAccessDeniedException.class);
            }
        }
        try (var actor = CurrentUserContext.use(CurrentUser.tenantUser("user", "User", "tenant-a"));
             var system = TenantContext.system("internal scope")) {
            assertThatThrownBy(() -> client.get(ID)).isInstanceOf(PlatformAccessDeniedException.class);
            assertThatThrownBy(access::issueUploadAccess).isInstanceOf(PlatformAccessDeniedException.class);
        }
        assertThat(requests).isEmpty();
    }

    private String transferScope(MuYunFileServerTransferAccessService access) throws Exception {
        String token = access.issueDownloadAccess("file_1").accessToken();
        var payload = json.readTree(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))));
        assertThat(payload.path("sub").asText()).isEqualTo("admin");
        return payload.path("tenant_id").asText();
    }

    @Test void neverFollowsRedirectsOrTurnsFailuresIntoRetirement() {
        var client = client();
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("u", "U", "tenant"))) {
            response = "secret upstream response";
            for (int code : new int[]{307,401,403,404,409,503}) {
                status = code;
                assertThatThrownBy(() -> client.create(request())).isInstanceOfSatisfying(FileReceptionException.class, e -> {
                    assertThat(e.kind()).isEqualTo(HTTP); assertThat(e.upstreamHttpStatus()).isEqualTo(code);
                    assertThat(e).hasNoCause().hasMessageNotContaining("secret").hasMessageNotContaining("token");
                });
            }
        }
        assertThat(requests).hasSize(6); assertThat(redirects).hasValue(0);
    }

    @Test void validatesSnapshotsAndManifestsStrictly() throws Exception {
        var client = client();
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("u", "U", "tenant"))) {
            String valid = snapshot("OPEN", Map.of());
            for (String invalid : List.of(valid.replace(ID, "22222222-2222-2222-2222-222222222222"),
                    valid.replace("OPEN", "UNKNOWN"), valid.replace("\"assets\":{}", "\"assets\":null"),
                    valid.replace("\"maxBytes\":100", "\"maxBytes\":\"100\""), snapshot("READY", Map.of()),
                    snapshot("CANCELLED", Map.of("x", "file")), "{\"data\":null}")) {
                response = invalid;
                assertThatThrownBy(() -> client.get(ID)).isInstanceOfSatisfying(FileReceptionException.class,
                        e -> assertThat(e.kind()).isEqualTo(PROTOCOL));
            }
            response = snapshot("READY", Map.of("different", "file"));
            assertThatThrownBy(() -> client.confirm(ID, List.of(new FileReceptionClient.ObjectEntry("x", "x", 1, null))))
                    .isInstanceOf(FileReceptionException.class);
            response = snapshot("OPEN", Map.of());
            assertThatThrownBy(() -> client.cancel(ID)).isInstanceOf(FileReceptionException.class);
            response = "{\"data\":[{\"key\":\"x\",\"sizeBytes\":\"0\"}]}";
            assertThatThrownBy(() -> client.objects(ID)).isInstanceOf(FileReceptionException.class);
        }
    }

    @Test void boundsResponseBodyAndTotalBodyWait() {
        properties.getReception().setMaxResponseBytes(128);
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("u", "U", "tenant"))) {
            response = "x".repeat(1024);
            var bounded = client();
            assertThatThrownBy(() -> bounded.get(ID)).isInstanceOf(FileReceptionException.class).hasNoCause();
            properties.getReception().setRequestTimeout(Duration.ofMillis(250));
            response = "{}"; stalled = true;
            var timed = client();
            org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(2), () -> assertThatThrownBy(() -> timed.get(ID))
                    .isInstanceOf(FileReceptionException.class).hasNoCause());
        }
    }

    @Test void assemblyIsSeparatelyOptInAndSupportsHostOverride() {
        var runner = new ApplicationContextRunner().withUserConfiguration(MuYunFileServerTransferConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("muyun.file-transfer.muyun-fileserver.enabled=true",
                        "muyun.file-transfer.muyun-fileserver.base-url=" + properties.getBaseUrl(),
                        "muyun.file-transfer.muyun-fileserver.issuer=test", "muyun.file-transfer.muyun-fileserver.secret=test");
        runner.run(context -> assertThat(context).doesNotHaveBean(FileReceptionClient.class));
        var enabled = runner.withPropertyValues("muyun.file-transfer.muyun-fileserver.reception.enabled=true",
                "muyun.file-transfer.muyun-fileserver.reception.service-token=private-reception-service-token");
        enabled.run(context -> assertThat(context).hasSingleBean(FileReceptionClient.class));
        var replacement = org.mockito.Mockito.mock(FileReceptionClient.class);
        enabled.withBean(FileReceptionClient.class, () -> replacement)
                .run(context -> assertThat(context.getBean(FileReceptionClient.class)).isSameAs(replacement));
    }
}
