package net.ximatai.muyun.spring.iam.welink;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.iam.federation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class WeLinkProtocolTest {
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger tickets = new AtomicInteger();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private Function<Request, Reply> handler;
    private WeLinkClient client;
    private MutableClock clock;
    private final ExternalAuthorizationContext context = new ExternalAuthorizationContext("state", "verifier",
            URI.create("https://workbench.test/app/"), URI.create("https://api.test/"));
    record Request(String path, String query, String body, String token) { }
    record Reply(int status, String body) { }
    private static Reply ok(String body) { return new Reply(200, body); }

    @BeforeEach void setup() throws Exception {
        tickets.set(0);
        clock = new MutableClock();
        client = new WeLinkClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), clock);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        handler = request -> {
            if (request.path().endsWith("/tickets")) return ok("{\"code\":0,\"access_token\":\"ticket-" + tickets.incrementAndGet() + "\",\"expires_in\":7200}");
            if (request.path().endsWith("/detail")) return ok("{\"code\":\"0\",\"data\":{\"deptCode\":\"root\",\"deptNameCn\":\"Root\"}}");
            return ok("{\"code\":0,\"data\":[],\"hasMore\":0}");
        };
        server.createContext("/", exchange -> {
            var request = new Request(exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("x-wlk-Authorization"));
            requests.add(request);
            Reply reply = handler.apply(request);
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(reply.status(), body.length); exchange.getResponseBody().write(body); }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void close() { server.stop(0); executor.shutdownNow(); }
    private IdentityProviderDescriptor provider(Map<String, String> overrides) {
        var config = new HashMap<>(Map.of("apiBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort(), "backoffMillis", "1"));
        config.putAll(overrides);
        return new IdentityProviderDescriptor("test", "welink", "corp", "app", "client", "sensitive-secret", config);
    }
    private IdentityProviderDescriptor provider() { return provider(Map.of()); }
    private WeLinkDirectoryProtocol directory() { return new WeLinkDirectoryProtocol(client); }

    @Test void cachesRefreshesAheadOfExpiryAndSeparatesCredentials() {
        var p = provider();
        directory().department(p, "root");
        directory().department(p, "root");
        assertThat(tickets).hasValue(1);
        clock.now = clock.now.plusSeconds(6900);
        directory().department(p, "root");
        assertThat(tickets).hasValue(2);
        clock.now = clock.now.plusSeconds(7201);
        directory().department(p, "root");
        assertThat(tickets).hasValue(3);
        var changed = new IdentityProviderDescriptor(p.id(), p.providerType(), "corp", "app", "other", "new-secret", p.configuration());
        directory().department(changed, "root");
        assertThat(tickets).hasValue(4);
        assertThat(requests.getFirst().body()).contains("client_id", "client_secret");
    }
    @Test void concurrentCallsShareOneRefresh() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) tasks.add(workers.submit(() -> directory().department(provider(), "root")));
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(tickets).hasValue(1);
    }
    @Test void invalidatesAndRetriesAuthenticationExactlyOnce() {
        var original = handler;
        handler = req -> req.path().endsWith("/detail") && "ticket-1".equals(req.token())
                ? ok("{\"code\":\"expired\"}") : original.apply(req);
        directory().department(provider(Map.of("authenticationFailureCodes", "expired")), "root");
        assertThat(tickets).hasValue(2);
        handler = req -> req.path().endsWith("/detail") ? new Reply(401, "sensitive-token") : original.apply(req);
        requests.clear();
        assertThatThrownBy(() -> directory().department(provider(), "root")).isInstanceOf(ExternalIdentityVerificationException.class);
        assertThat(requests.stream().filter(r -> r.path().endsWith("/detail"))).hasSize(2);
    }
    @Test void paginatesSafelyAndMapsMembershipPrimaryAndStatuses() {
        var original = handler;
        handler = req -> req.path().endsWith("/user/list") ? ok("""
                {"code":0,"hasMore":1,"data":[
                  {"userId":"alice","employeeId":"001","userNameCn":"Alice","userStatus":3,
                   "mobileNumber":"sensitive-mobile","userEmail":"alice@example.test","mainDeptCode":"b","deptCodes":["a","b"]},
                  {"userId":"bob","userNameCn":"Bob","userStatus":6,"deptCodes":"a,b"}]}
                """) : original.apply(req);
        var p = provider(Map.of("pageSize", "2", "departedStatuses", "6"));
        var first = directory().departmentPeople(p, "a", null, 200);
        assertThat(first.nextCursor()).isEqualTo("2"); // Numeric 1 does not stop a full page by default.
        assertThat(first.items()).first().satisfies(person -> {
            assertThat(person.primaryDepartmentId()).isEqualTo("b");
            assertThat(person.departmentIds()).containsExactly("a", "b");
            assertThat(person.status()).isEqualTo("active");
            assertThat(person.employeeNo()).isEqualTo("001");
            assertThat(person.mobile()).isEqualTo("sensitive-mobile");
        });
        assertThat(first.items().get(1).status()).isEqualTo("departed");
        assertThat(directory().departmentPeople(provider(Map.of("pageSize", "2")), "a", null, 200).items().get(1).status()).isEqualTo("disabled");
        assertThat(directory().supportsPersonLookup(p)).isFalse();
        handler = req -> req.path().endsWith("/user/list") ? ok("{\"code\":0,\"hasMore\":1,\"data\":[{\"userId\":\"x\",\"userNameCn\":\"X\"}]}") : original.apply(req);
        assertThat(directory().departmentPeople(p, "a", "2", 200).nextCursor()).isNull(); // Short page wins.
        assertThat(requests).anySatisfy(r -> assertThat(r.query()).contains("pageNo=2", "pageSize=2"));
    }
    @Test void honorsExplicitNoMoreAndBoundsNonTerminatingPages() {
        var original = handler;
        handler = req -> req.path().endsWith("/user/list") ? ok("{\"code\":0,\"hasMore\":false,\"data\":[{\"userId\":\"x\",\"userNameCn\":\"X\"}]}") : original.apply(req);
        assertThat(directory().departmentPeople(provider(), "a", null, 1).nextCursor()).isNull();
        handler = req -> req.path().endsWith("/user/list") ? ok("{\"code\":0,\"hasMore\":1,\"data\":[{\"userId\":\"x\",\"userNameCn\":\"X\"}]}") : original.apply(req);
        assertThat(directory().departmentPeople(provider(Map.of("noMoreValue", "1")), "a", null, 1).nextCursor()).isNull();
        assertThatThrownBy(() -> directory().departmentPeople(provider(Map.of("maxPages", "2")), "a", "3", 1)).isInstanceOf(ExternalIdentityVerificationException.class);
    }
    @Test void normalizesUnorderedDepartmentTreeAndRejectsBrokenHierarchy() {
        var original = handler;
        handler = req -> req.path().endsWith("/department/list") ? ok("""
                {"code":0,"hasMore":0,"data":[
                  {"deptCode":"child","parentCode":"parent","deptNameCn":"Child"},
                  {"deptCode":"parent","parentCode":"root","deptNameEn":"Parent"}]}
                """) : original.apply(req);
        var directory = new WeLinkDirectoryProtocol(client, clock);
        var first = directory.departments(provider(), "root", null, 1);
        assertThat(first.items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("root");
        assertThat(requests).anySatisfy(r -> assertThat(r.query()).contains("recursiveflag=1", "pageNo=1"));
        var second = directory.departments(provider(), "root", first.nextCursor(), 1);
        assertThat(second.items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("parent");
        assertThat(directory.departments(provider(), "root", second.nextCursor(), 1).items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("child");
        handler = req -> req.path().endsWith("/department/list") ? ok("{\"code\":0,\"data\":[{\"deptCode\":\"x\",\"parentCode\":\"missing\",\"deptNameCn\":\"X\"}]}") : original.apply(req);
        int before = requests.size();
        assertThat(directory.departments(provider(), "root", second.nextCursor(), 1).items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("child");
        assertThat(requests).hasSize(before); // Frozen snapshot survives upstream changes and cursor replay.
        assertThatThrownBy(() -> directory.departments(provider(), "another-root", first.nextCursor(), 1)).isInstanceOf(ExternalIdentityVerificationException.class);
        assertThatThrownBy(() -> directory.departments(provider(Map.of("timeoutMillis", "200")), "root", first.nextCursor(), 1)).isInstanceOf(ExternalIdentityVerificationException.class);
        clock.now = clock.now.plusSeconds(301);
        assertThatThrownBy(() -> directory.departments(provider(), "root", first.nextCursor(), 1)).isInstanceOf(ExternalIdentityVerificationException.class);
        assertThatThrownBy(() -> directory().departments(provider(), "root", null, 100)).isInstanceOf(ExternalIdentityVerificationException.class);
    }
    @Test void rootParentSentinelsAreExplicitAndConfigurable() {
        var original = handler;
        handler = req -> req.path().endsWith("/detail") ? ok("{\"code\":0,\"data\":{\"deptCode\":\"root\",\"parentCode\":\"0\",\"deptNameCn\":\"Root\"}}") : original.apply(req);
        assertThat(directory().department(provider(), "root").parentId()).isEqualTo("0");
        assertThat(directory().department(provider(Map.of("rootParentCodes", "0,-1")), "root").parentId()).isNull();
    }
    @Test void exchangesCodeInQueryOrFormAndOnlyTrustsBackendIdentity() throws Exception {
        handler = req -> req.path().endsWith("/token") ? ok("{\"message\":\"success\",\"access_token\":\"sensitive-token\"}")
                : ok("{\"code\":0,\"userId\":\"verified-user\"}");
        var login = new WeLinkLoginProtocol(client);
        for (String mode : List.of("query", "form")) {
            var p = provider(Map.of("tokenParameterMode", mode));
            var identity = login.verify(p, Map.of("code", "sensitive-code", "state", "state", "userId", "attacker"), context);
            assertThat(identity.externalUserId()).isEqualTo("verified-user");
            assertThat(identity.externalEnterpriseId()).isNull();
            assertThat(identity.authenticatedAt()).isNotNull();
            assertThat(login.singleEnterpriseApplication(p)).isTrue();
            var request = requests.get(requests.size() - 2);
            assertThat(mode.equals("query") ? request.query() : request.body()).contains("client_secret=sensitive-secret", "redirect_uri=https%3A%2F%2Fworkbench.test%2Fapp%2F");
            assertThat(mode.equals("query") ? request.body() : request.query()).isNullOrEmpty();
            assertThat(requests.getLast().token()).isEqualTo("sensitive-token");
        }
        assertThat(login.authorizationUri(provider(), context).toString()).contains("scope=snsapi_login").doesNotContain("code_challenge", "secret", "verifier");
        assertThat(new ObjectMapper().writeValueAsString(login.qrCode(provider(), context))).doesNotContain("sensitive", "verifier");
        assertThat(login.identityScopeConfigurationKeys()).contains("apiBaseUrl", "loginBaseUrl");
        assertThatThrownBy(() -> login.verify(provider(), Map.of("state", "other", "code", "x"), context)).isInstanceOf(ExternalIdentityVerificationException.class);
    }
    @Test void boundsRateRetriesAndPacesRequests() {
        var original = handler;
        var failures = new AtomicInteger();
        handler = req -> req.path().endsWith("/detail") && failures.getAndIncrement() < 2
                ? new Reply(429, "sensitive-token") : original.apply(req);
        long started = System.nanoTime();
        directory().department(provider(Map.of("minRequestIntervalMillis", "15")), "root");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isGreaterThanOrEqualTo(40);
        handler = req -> req.path().endsWith("/detail") ? ok("{\"code\":\"limited\"}") : original.apply(req);
        requests.clear();
        assertThatThrownBy(() -> directory().department(provider(Map.of("rateLimitCodes", "limited")), "root")).isInstanceOf(ExternalIdentityVerificationException.class);
        assertThat(requests.stream().filter(r -> r.path().endsWith("/detail"))).hasSize(3);
    }
    @Test void errorsAndTimeoutsDoNotLeakSecretsOrUpstreamMessages(CapturedOutput output) {
        var original = handler;
        String sensitive = "sensitive-secret sensitive-code sensitive-token sensitive-mobile";
        handler = req -> req.path().endsWith("/detail") ? ok("{\"code\":\"bad\",\"message\":\"" + sensitive + "\"}") : original.apply(req);
        assertThatThrownBy(() -> directory().department(provider(), "root")).hasMessage("WeLink request rejected").hasNoCause();
        handler = req -> req.path().endsWith("/token") ? ok("{\"message\":\"" + sensitive + "\"}") : original.apply(req);
        assertThatThrownBy(() -> new WeLinkLoginProtocol(client).verify(provider(), Map.of("state", "state", "code", "sensitive-code"), context)).hasMessage("WeLink request rejected").hasNoCause();
        handler = req -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return original.apply(req);
        };
        assertThatThrownBy(() -> directory().department(provider(Map.of("timeoutMillis", "20")), "root"))
                .isInstanceOf(ExternalIdentityVerificationException.class).hasNoCause();
        assertThat(output.getAll()).doesNotContain("sensitive-secret", "sensitive-code", "sensitive-token", "sensitive-mobile");
    }
    @Test void validatesConfigAndRequiresBothOptInSwitches() {
        for (var config : List.of(Map.of("apiBaseUrl", "http://unsafe.test"), Map.of("scope", "admin"),
                Map.of("pageSize", "0"), Map.of("activeStatuses", "3", "departedStatuses", "3"),
                Map.of("tokenParameterMode", "json"), Map.of("rateLimitCodes", "0"), Map.of("authenticationFailureCodes", "401,,403"), Map.of("apiBaseUrl", "https://host.test?secret=value"))) {
            assertThatThrownBy(() -> new WeLinkLoginProtocol(client).validateConfiguration(config)).isInstanceOf(IllegalArgumentException.class);
        }
        var runner = new ApplicationContextRunner().withUserConfiguration(WeLinkAutoConfiguration.class);
        runner.run(c -> assertThat(c).doesNotHaveBean(WeLinkLoginProtocol.class));
        runner.withPropertyValues("muyun.iam.federation.welink.enabled=true").run(c -> assertThat(c).doesNotHaveBean(WeLinkLoginProtocol.class));
        runner.withPropertyValues("muyun.iam.federation.enabled=true", "muyun.iam.federation.welink.enabled=true")
                .run(c -> assertThat(c).hasSingleBean(WeLinkLoginProtocol.class).hasSingleBean(WeLinkDirectoryProtocol.class));
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
