package net.ximatai.muyun.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.federation.IdentityProvider;
import net.ximatai.muyun.spring.iam.federation.IdentityProviderDescriptor;
import net.ximatai.muyun.spring.iam.federation.IdentityProviderService;
import net.ximatai.muyun.spring.iam.federation.MockExternalLoginProtocol;
import net.ximatai.muyun.spring.iam.federation.TenantIdentityPolicy;
import net.ximatai.muyun.spring.iam.federation.TenantIdentityPolicyService;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.PasswordStatus;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.iam.user.UserSession;
import net.ximatai.muyun.spring.iam.user.UserSessionDao;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import net.ximatai.muyun.spring.iam.federation.ExternalAuthorizationContext;
import net.ximatai.muyun.spring.iam.federation.ExternalLoginProtocol;
import net.ximatai.muyun.spring.iam.federation.VerifiedExternalIdentity;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * External sign-in and binding through real HTTP with the development mock provider, from transaction start
 * over the provider round trip to completion by the initiating browser, against a real database.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {MuYunSpringApplication.class, ExternalIdentityFederationIT.ForeignProtocolConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalIdentityFederationIT {
    // Started eagerly: the per-class seed needs the Spring context before JUnit would start a @Container.
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }

    private static final String TENANT = "federation_it";
    private static final String PROVIDER = "mock_idp";
    private static final String FOREIGN_PROVIDER = "foreign_idp";
    private static final String PASSWORD = "Federation123!";

    @Autowired TenantService tenantService;
    @Autowired UserAccountService userAccountService;
    @Autowired IdentityProviderService identityProviderService;
    @Autowired TenantIdentityPolicyService tenantIdentityPolicyService;
    @Autowired UserSessionDao userSessionDao;
    @Autowired ApplicationContext applicationContext;
    @Autowired JdbcTemplate jdbcTemplate;

    @LocalServerPort int port;
    private TestRestTemplate restTemplate;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
        registry.add("muyun.runtime.mode", () -> "development");
        registry.add("muyun.iam.federation.enabled", () -> "true");
        registry.add("muyun.iam.federation.mock.enabled", () -> "true");
        registry.add("muyun.iam.federation.api-base-url", () -> "http://platform.test/api");
        registry.add("muyun.iam.federation.workbench-url", () -> "http://workbench.test/app/");
    }

    @BeforeAll
    void seed() {
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("federation-it", "federation-it"));
             var system = TenantContext.system("external identity federation integration test")) {
            Tenant tenant = new Tenant();
            tenant.setAlias(TENANT);
            tenant.setTitle("Federation tenant");
            tenant.setEnabled(true);
            tenantService.insert(tenant);
            IdentityProvider provider = new IdentityProvider();
            provider.setId(PROVIDER);
            provider.setTitle("模拟身份源");
            provider.setProviderType(MockExternalLoginProtocol.PROVIDER_TYPE);
            provider.setEnabled(true);
            identityProviderService.insert(provider);
            IdentityProvider foreign = new IdentityProvider();
            foreign.setId(FOREIGN_PROVIDER);
            foreign.setTitle("企业身份源");
            foreign.setProviderType(ForeignEnterpriseProtocol.TYPE);
            foreign.setExternalEnterpriseId("expected-corp");
            foreign.setEnabled(true);
            identityProviderService.insert(foreign);
            try (var tenantScope = TenantContext.use(TENANT)) {
                for (String providerId : java.util.List.of(PROVIDER, FOREIGN_PROVIDER)) {
                    TenantIdentityPolicy policy = new TenantIdentityPolicy();
                    policy.setProviderId(providerId);
                    policy.setEnabled(true);
                    tenantIdentityPolicyService.insert(policy);
                }
                createUser("alice");
                createUser("bob");
            }
        }
    }

    @BeforeEach
    void setUpRestTemplate() {
        restTemplate = new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + port + "/api"));
    }

    @Test
    void bindsThenSignsInWithTheExternalIdentityAndRejectsReplayConflictAndUnboundIdentities() throws Exception {
        JsonNode loginContext = restTemplate.getForObject("/iam.auth/login-context?tenantId=" + TENANT, JsonNode.class);
        assertThat(loginContext.path("externalLoginMethods").toString()).contains(PROVIDER, "模拟身份源");

        String aliceToken = passwordLogin("alice");
        JsonNode bound = completeFlow(post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(), aliceToken), "emp-001");
        assertThat(bound.path("status").asText()).isEqualTo("succeeded");
        assertThat(bound.path("binding").path("externalUserId").asText()).isEqualTo("emp-001");

        JsonNode signedIn = completeFlow(startLogin(), "emp-001");
        assertThat(signedIn.path("status").asText()).isEqualTo("succeeded");
        String externalToken = signedIn.path("login").path("token").asText();
        assertThat(signedIn.path("login").path("currentUser").path("username").asText()).isEqualTo("alice");
        JsonNode context = exchange(HttpMethod.GET, "/iam.auth/context", null, externalToken).getBody();
        assertThat(context.path("username").asText()).isEqualTo("alice");
        UserSession externalSession = userSessionDao.query(Criteria.of().eq("userId", context.path("userId").asText())
                .eq("authenticationMethod", "external:" + PROVIDER), new PageRequest(0, 1)).getFirst();
        assertThat(externalSession.getPasswordChangeRequired()).isFalse();

        JsonNode unbound = completeFlow(startLogin(), "emp-999");
        assertThat(unbound.path("status").asText()).isEqualTo("denied");
        assertThat(unbound.path("failure").asText()).isEqualTo("identityNotBound");
        assertThat(unbound.path("login").isMissingNode() || unbound.path("login").isNull()).isTrue();

        JsonNode conflict = completeFlow(post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(),
                passwordLogin("bob")), "emp-001");
        assertThat(conflict.path("failure").asText()).isEqualTo("identityConflict");

        JsonNode identities = exchange(HttpMethod.GET, "/iam.auth/external/identities", null, aliceToken).getBody();
        String bindingId = identities.path("bindings").get(0).path("id").asText();
        assertThat(exchange(HttpMethod.POST, "/iam.auth/external/identities/" + bindingId + "/delete", Map.of(),
                aliceToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completeFlow(startLogin(), "emp-001").path("failure").asText()).isEqualTo("identityNotBound");
    }

    @Test
    void completesOnlyFromTheInitiatingBrowserAndOnlyOnce() throws Exception {
        String aliceToken = passwordLogin("alice");
        completeFlow(post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(), aliceToken), "emp-777");
        JsonNode start = startLogin();
        String code = authorize(start, "emp-777");

        // A browser that did not start the transaction holds neither the proof nor a usable state/proof pair.
        ResponseEntity<JsonNode> withoutProof = complete(start, code, "forged-proof");
        assertThat(withoutProof.getStatusCode().is4xxClientError()).isTrue();
        assertThat(withoutProof.getBody().path("code").asText()).isEqualTo("iam.federation.state-invalid");

        ResponseEntity<JsonNode> completed = complete(start, code, start.path("browserProof").asText());
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().path("login").path("token").asText()).isNotBlank();

        ResponseEntity<JsonNode> replay = complete(start, code, start.path("browserProof").asText());
        assertThat(replay.getStatusCode().is4xxClientError()).isTrue();
        assertThat(replay.getBody().path("code").asText()).isEqualTo("iam.federation.state-invalid");
    }

    @Test
    void mockAuthorizationPageOnlyReturnsToTheConfiguredWorkbench() throws Exception {
        HttpResponse<String> foreign = http.send(HttpRequest.newBuilder(URI.create(base()
                        + "/iam.auth/external/mock/authorize?state=s&redirect_uri=" + encode("https://evil.example/")))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(foreign.statusCode()).isEqualTo(400);
    }

    @Test
    void expiresTransactionsWhoseCallbackArrivesTooLate() throws Exception {
        JsonNode start = startLogin();
        jdbcTemplate.update("update iam_external_login_transaction set expires_at = now() - interval '1 second' where id = ?",
                start.path("transactionId").asText());
        JsonNode result = completeFlow(start, "emp-late");
        assertThat(result.path("status").asText()).isEqualTo("expired");
    }

    @Test
    void requiresARecentSignInToStartBinding() {
        String token = passwordLogin("alice");
        jdbcTemplate.update("update iam_user_session set issued_at = now() - interval '10 minutes' where token_hash = ?",
                sha256(token));
        ResponseEntity<JsonNode> bind = post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(), token);
        assertThat(bind.getStatusCode().is4xxClientError()).isTrue();
        assertThat(bind.getBody().path("code").asText()).isEqualTo("iam.federation.reauthentication-required");
    }

    @Test
    void deniesIdentitiesFromAnotherEnterpriseThroughAPluggedProtocol() {
        ResponseEntity<JsonNode> start = post("/iam.auth/external/" + FOREIGN_PROVIDER + "/login",
                Map.of("tenantId", TENANT), null);
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = post("/iam.auth/external/" + FOREIGN_PROVIDER + "/complete", Map.of(
                "state", start.getBody().path("state").asText(),
                "browserProof", start.getBody().path("browserProof").asText(),
                "parameters", Map.of("state", start.getBody().path("state").asText(), "code", "anyone")), null).getBody();
        assertThat(result.path("status").asText()).isEqualTo("denied");
        assertThat(result.path("failure").asText()).isEqualTo("enterpriseMismatch");
    }

    @Test
    void rejectsCompletionsWithUnknownState() {
        ResponseEntity<JsonNode> response = post("/iam.auth/external/" + PROVIDER + "/complete", Map.of(
                "state", "unknown", "browserProof", "unknown", "parameters", Map.of("code", "mock:emp-001")), null);
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody().path("code").asText()).isEqualTo("iam.federation.state-invalid");
    }

    @Test
    void keepsTheAnonymousSurfaceExplicitWhenFederationIsEnabled() {
        var mappings = applicationContext.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        var anonymousPaths = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().hasMethodAnnotation(
                        net.ximatai.muyun.spring.common.platform.AnonymousEndpoint.class))
                .flatMap(entry -> entry.getKey().getPatternValues().stream())
                .collect(Collectors.toSet());
        assertThat(anonymousPaths).containsExactlyInAnyOrder(
                "/iam.auth/login", "/iam.auth/logout", "/platform.localization/catalog",
                "/iam.auth/external/{providerId}/login", "/iam.auth/external/{providerId}/complete",
                "/iam.auth/external/mock/authorize");
    }

    private JsonNode startLogin() {
        ResponseEntity<JsonNode> start = post("/iam.auth/external/" + PROVIDER + "/login", Map.of("tenantId", TENANT), null);
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.OK);
        return start.getBody();
    }

    private JsonNode completeFlow(ResponseEntity<JsonNode> start, String externalUserId) throws Exception {
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.OK);
        return completeFlow(start.getBody(), externalUserId);
    }

    private JsonNode completeFlow(JsonNode start, String externalUserId) throws Exception {
        ResponseEntity<JsonNode> completion = complete(start, authorize(start, externalUserId),
                start.path("browserProof").asText());
        assertThat(completion.getStatusCode()).isEqualTo(HttpStatus.OK);
        return completion.getBody();
    }

    /**
     * Opens the mock authorization page, checks it returns to the workbench, and yields the code the provider
     * would append to the workbench address.
     */
    private String authorize(JsonNode start, String externalUserId) throws Exception {
        URI authorization = URI.create(start.path("authorizationUrl").asText());
        Map<String, String> query = query(authorization);
        assertThat(query.get("redirect_uri")).isEqualTo("http://workbench.test/app/");
        assertThat(query.get("state")).isEqualTo(start.path("state").asText());
        String page = http.send(HttpRequest.newBuilder(URI.create(base() + "/iam.auth/external/mock/authorize?"
                        + authorization.getRawQuery())).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(page).contains("模拟身份源授权").contains("action=\"http://workbench.test/app/\"");
        return "mock:" + externalUserId;
    }

    /** What the workbench submits after the provider returned the browser with state and code. */
    private ResponseEntity<JsonNode> complete(JsonNode start, String code, String browserProof) {
        String state = start.path("state").asText();
        return post("/iam.auth/external/" + start.path("providerId").asText(PROVIDER) + "/complete", Map.of(
                "state", state, "browserProof", browserProof, "parameters", Map.of("state", state, "code", code)), null);
    }

    private String passwordLogin(String username) {
        ResponseEntity<JsonNode> login = restTemplate.postForEntity("/iam.auth/login",
                Map.of("tenantId", TENANT, "username", username, "password", PASSWORD), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return login.getBody().path("token").asText();
    }

    private ResponseEntity<JsonNode> post(String path, Object body, String token) {
        return exchange(HttpMethod.POST, path, body, token);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void createUser(String username) {
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEnabled(true);
        user.setPasswordStatus(PasswordStatus.NORMAL);
        userAccountService.createUser(user, PASSWORD);
    }

    private String base() {
        return "http://localhost:" + port + "/api";
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A third-party protocol registered as a plain bean, confirming identities of a different enterprise. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ForeignProtocolConfiguration {
        @Bean
        ExternalLoginProtocol foreignEnterpriseProtocol() {
            return new ForeignEnterpriseProtocol();
        }
    }

    static final class ForeignEnterpriseProtocol implements ExternalLoginProtocol {
        static final String TYPE = "foreign-test";

        @Override
        public String providerType() {
            return TYPE;
        }

        @Override
        public URI authorizationUri(IdentityProviderDescriptor provider, ExternalAuthorizationContext context) {
            return URI.create("https://idp.test/authorize?state=" + encode(context.state()));
        }

        @Override
        public VerifiedExternalIdentity verify(IdentityProviderDescriptor provider, Map<String, String> callbackParameters,
                                               ExternalAuthorizationContext context) {
            return new VerifiedExternalIdentity("foreign-corp", callbackParameters.get("code"), null,
                    java.time.Instant.now());
        }
    }
}
