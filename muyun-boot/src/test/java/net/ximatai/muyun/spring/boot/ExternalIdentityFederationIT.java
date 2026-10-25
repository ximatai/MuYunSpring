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
    private static final String SECOND_PROVIDER = "mock_idp_b";
    private static final String PASSWORD = "Federation123!";

    @Autowired TenantService tenantService;
    @Autowired UserAccountService userAccountService;
    @Autowired IdentityProviderService identityProviderService;
    @Autowired TenantIdentityPolicyService tenantIdentityPolicyService;
    @Autowired UserSessionDao userSessionDao;
    @Autowired ApplicationContext applicationContext;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @Autowired net.ximatai.muyun.spring.iam.federation.ExternalIdentityService externalIdentityService;
    @Autowired net.ximatai.muyun.spring.iam.user.UserSessionService userSessionService;
    @Autowired BlockingProtocol blockingProtocol;
    @Autowired StatusProtocol statusProtocol;

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
            IdentityProvider second = new IdentityProvider();
            second.setId(SECOND_PROVIDER);
            second.setTitle("第二模拟身份源");
            second.setProviderType(MockExternalLoginProtocol.PROVIDER_TYPE);
            second.setEnabled(true);
            identityProviderService.insert(second);
            IdentityProvider blocking = new IdentityProvider();
            blocking.setId(BlockingProtocol.TYPE);
            blocking.setTitle("Blocking test provider");
            blocking.setProviderType(BlockingProtocol.TYPE);
            blocking.setEnabled(true);
            identityProviderService.insert(blocking);
            try (var tenantScope = TenantContext.use(TENANT)) {
                for (String providerId : java.util.List.of(PROVIDER, FOREIGN_PROVIDER, SECOND_PROVIDER, BlockingProtocol.TYPE)) {
                    TenantIdentityPolicy policy = new TenantIdentityPolicy();
                    policy.setProviderId(providerId);
                    policy.setEnabled(true);
                    tenantIdentityPolicyService.insert(policy);
                }
                createUser("alice");
                createUser("bob");
                createUser("carol");
                createUser("dave");
                createUser("erin");
            }
        }
    }

    @Test
    void refreshesExpiredSourceOutsideTransactionsRejectsFailuresAndRevokesOnDepartureAndUnbind() throws Exception {
        String providerId = StatusProtocol.TYPE;
        String userId;
        String bindingId;
        try (var current = CurrentUserContext.use(CurrentUser.systemUser("status-it", "status-it"));
             var system = TenantContext.system("source status integration test")) {
            var provider = new IdentityProvider();
            provider.setId(providerId);
            provider.setTitle("Status provider");
            provider.setProviderType(providerId);
            provider.setExternalEnterpriseId("status-corp");
            provider.setIdentityNamespace("status-test");
            provider.setEnabled(true);
            identityProviderService.insert(provider);
            try (var tenant = TenantContext.use(TENANT)) {
                var policy = new TenantIdentityPolicy();
                policy.setProviderId(providerId);
                policy.setEnabled(true);
                tenantIdentityPolicyService.insert(policy);
                createUser("source-status-user");
                userId = userAccountService.list(Criteria.of().eq("username", "source-status-user"), new PageRequest(0, 1)).getFirst().getId();
                var binding = new net.ximatai.muyun.spring.iam.federation.ExternalIdentity();
                binding.setProviderId(providerId);
                binding.setIdentityNamespace("status-test");
                binding.setExternalEnterpriseId("status-corp");
                binding.setExternalUserId("source-status-user");
                binding.setUserId(userId);
                binding.setBindingSource(net.ximatai.muyun.spring.iam.federation.ExternalIdentityBindingSource.DIRECTORY_SYNC);
                binding.setBoundAt(java.time.Instant.now().minusSeconds(172800));
                binding.setSourceStatus("active");
                externalIdentityService.insert(binding);
                bindingId = binding.getId();
            }
        }
        statusProtocol.fail = false;
        statusProtocol.status = "active";
        var success = statusCompletion();
        assertThat(success.path("status").asText()).isEqualTo("succeeded");
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(externalIdentityService.select(bindingId).getSourceCheckedAt()).isNotNull();
            assertThat(userSessionDao.query(Criteria.of().eq("userId", userId), new PageRequest(0, 20)))
                    .anyMatch(session -> session.getRevokedAt() == null);
        }
        jdbcTemplate.update("update iam_external_identity set source_checked_at = current_timestamp - interval '2 days' where id = ?", bindingId);
        statusProtocol.fail = true;
        assertThat(statusCompletion().path("failure").asText()).isEqualTo("providerUnavailable");
        statusProtocol.fail = false;
        statusProtocol.status = "departed";
        assertThat(statusCompletion().path("status").asText()).isEqualTo("denied");
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(externalIdentityService.select(bindingId).getSourceStatus()).isEqualTo("departed");
            assertThat(userAccountService.select(userId).getEnabled()).isTrue();
            assertThat(userSessionDao.query(Criteria.of().eq("userId", userId), new PageRequest(0, 20)))
                    .allMatch(session -> session.getRevokedAt() != null);
        }
        // A fresh inactive status must be rejected without refreshing it back to active.
        statusProtocol.status = "active";
        int calls = statusProtocol.calls;
        assertThat(statusCompletion().path("failure").asText()).isEqualTo("accountUnavailable");
        assertThat(statusProtocol.calls).isEqualTo(calls);
        passwordLogin("source-status-user");
        try (var current = CurrentUserContext.use(CurrentUser.systemUser("status-it", "status-it"));
             var tenant = TenantContext.use(TENANT)) {
            externalIdentityService.delete(bindingId);
            assertThat(externalIdentityService.select(bindingId)).isNull();
            assertThat(externalIdentityService.isAutoRebindBlocked(providerId, "source-status-user")).isTrue();
            assertThat(userSessionDao.query(Criteria.of().eq("userId", userId), new PageRequest(0, 20)))
                    .allMatch(session -> session.getRevokedAt() != null);
        }
    }

    private JsonNode statusCompletion() {
        var start = post("/iam.auth/external/" + StatusProtocol.TYPE + "/login", Map.of("tenantId", TENANT), null).getBody();
        String state = start.path("state").asText();
        return post("/iam.auth/external/" + StatusProtocol.TYPE + "/complete", Map.of("state", state,
                "browserProof", start.path("browserProof").asText(),
                "parameters", Map.of("state", state, "code", "source-status-user")), null).getBody();
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
        String challenge = "c".repeat(43);
        HttpResponse<String> foreign = http.send(HttpRequest.newBuilder(URI.create(base()
                        + "/iam.auth/external/mock/authorize?state=s&code_challenge=" + challenge
                        + "&code_challenge_method=S256&redirect_uri=" + encode("https://evil.example/")))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(foreign.statusCode()).isEqualTo(400);
        HttpResponse<String> plain = http.send(HttpRequest.newBuilder(URI.create(base()
                        + "/iam.auth/external/mock/authorize?state=s&code_challenge=" + challenge
                        + "&code_challenge_method=plain&redirect_uri=" + encode("http://workbench.test/app/")))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(plain.statusCode()).isEqualTo(400);
    }

    @Test
    void refusesAnAuthorizationCodeTransplantedIntoAnotherTransaction() throws Exception {
        String carolToken = passwordLogin("carol");
        assertThat(completeFlow(post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(), carolToken), "emp-carol")
                .path("status").asText()).isEqualTo("succeeded");
        JsonNode victim = startLogin();
        String leakedCode = authorize(victim, "emp-carol");
        JsonNode attacker = startLogin();

        ResponseEntity<JsonNode> transplanted = complete(attacker, leakedCode, attacker.path("browserProof").asText());
        assertThat(transplanted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transplanted.getBody().path("status").asText()).isEqualTo("denied");
        assertThat(transplanted.getBody().path("failure").asText()).isEqualTo("providerRejected");
        assertThat(transplanted.getBody().path("login").isMissingNode() || transplanted.getBody().path("login").isNull())
                .isTrue();

        // The returned state must be the state of the transaction being completed.
        ResponseEntity<JsonNode> foreignState = post("/iam.auth/external/" + PROVIDER + "/complete", Map.of(
                "state", attacker.path("state").asText(), "browserProof", attacker.path("browserProof").asText(),
                "parameters", Map.of("state", victim.path("state").asText(), "code", leakedCode)), null);
        assertThat(foreignState.getBody().path("code").asText()).isEqualTo("iam.federation.state-invalid");

        assertThat(complete(victim, leakedCode, victim.path("browserProof").asText()).getBody()
                .path("status").asText()).isEqualTo("succeeded");
    }

    @Test
    void abandonsABindWhoseInitiatingSessionEnded() throws Exception {
        String daveToken = passwordLogin("dave");
        JsonNode start = post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(), daveToken).getBody();
        String code = authorize(start, "emp-dave");
        assertThat(post("/iam.auth/logout", Map.of(), daveToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode result = complete(start, code, start.path("browserProof").asText()).getBody();
        assertThat(result.path("status").asText()).isEqualTo("denied");
        assertThat(result.path("failure").asText()).isEqualTo("sessionEnded");
        assertThat(jdbcTemplate.queryForObject("select count(*) from iam_external_identity where external_user_id = ?",
                Integer.class, "emp-dave")).isZero();
    }

    @Test
    void keepsTheLastBindingThatCanStillSignInWhenTheAccountHasNoPassword() throws Exception {
        String erinToken = passwordLogin("erin");
        for (String providerId : java.util.List.of(PROVIDER, SECOND_PROVIDER)) {
            JsonNode start = post("/iam.auth/external/" + providerId + "/bind", Map.of(), erinToken).getBody();
            JsonNode bound = post("/iam.auth/external/" + providerId + "/complete", Map.of(
                    "state", start.path("state").asText(), "browserProof", start.path("browserProof").asText(),
                    "parameters", Map.of("state", start.path("state").asText(),
                            "code", authorize(start, "emp-erin-" + providerId))), null).getBody();
            assertThat(bound.path("status").asText()).isEqualTo("succeeded");
        }
        jdbcTemplate.update("update iam_user set password_status = 'none', password_hash = null where username = 'erin'");
        jdbcTemplate.update("update iam_tenant_identity_policy set login_enabled = false where provider_id = ?",
                SECOND_PROVIDER);
        try {
            JsonNode identities = exchange(HttpMethod.GET, "/iam.auth/external/identities", null, erinToken).getBody();
            String usableBinding = java.util.stream.StreamSupport.stream(identities.path("bindings").spliterator(), false)
                    .filter(binding -> PROVIDER.equals(binding.path("providerId").asText()))
                    .findFirst().orElseThrow().path("id").asText();
            ResponseEntity<JsonNode> unbind = exchange(HttpMethod.POST,
                    "/iam.auth/external/identities/" + usableBinding + "/delete", Map.of(), erinToken);
            assertThat(unbind.getStatusCode().is4xxClientError()).isTrue();
            assertThat(unbind.getBody().path("code").asText()).isEqualTo("iam.federation.last-sign-in-method");
        } finally {
            jdbcTemplate.update("update iam_tenant_identity_policy set login_enabled = true where provider_id = ?",
                    SECOND_PROVIDER);
        }
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

    @Test
    void authorityEditsRecheckBindingsAfterAConcurrentFirstBindCommits() throws Exception {
        String providerId = "scope_race_idp";
        String username = "scope_race_user";
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("federation-it", "Test"));
             var system = TenantContext.system("authority race fixture")) {
            IdentityProvider provider = new IdentityProvider();
            provider.setId(providerId); provider.setTitle("Scope race");
            provider.setProviderType(BlockingProtocol.TYPE); provider.setEnabled(true);
            identityProviderService.insert(provider);
            try (var tenant = TenantContext.use(TENANT)) {
                TenantIdentityPolicy policy = new TenantIdentityPolicy();
                policy.setProviderId(providerId); policy.setEnabled(true);
                tenantIdentityPolicyService.insert(policy);
                createUser(username);
            }
        }
        String token = passwordLogin(username);
        String userId = jdbcTemplate.queryForObject("select id from iam_user where tenant_id = ? and username = ?",
                String.class, TENANT, username);
        JsonNode start = post("/iam.auth/external/" + providerId + "/bind", Map.of(), token).getBody();
        var accountLocked = new java.util.concurrent.CountDownLatch(1);
        var releaseAccount = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var hold = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForList("select id from iam_user where id = ? for update", userId);
                accountLocked.countDown();
                await(releaseAccount);
            }));
            try {
                assertThat(accountLocked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var bind = executor.submit(() -> post("/iam.auth/external/" + providerId + "/complete", Map.of(
                        "state", start.path("state").asText(), "browserProof", start.path("browserProof").asText(),
                        "parameters", Map.of("code", "scope-race-identity")), null).getBody());
                // The bind holds provider/policy locks and now waits for the initiating account.
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                while (jdbcTemplate.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
                                + "and wait_event_type = 'Lock' and query like 'select id from iam_user where tenant_id%for update'", Integer.class) == 0
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(jdbcTemplate.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
                        + "and wait_event_type = 'Lock' and query like 'select id from iam_user where tenant_id%for update'", Integer.class)).isPositive();
                var edit = executor.submit(() -> {
                    try (var actor = CurrentUserContext.use(CurrentUser.systemUser("federation-it", "Test"));
                         var system = TenantContext.system("authority race edit")) {
                        var provider = identityProviderService.select(providerId);
                        provider.setExternalEnterpriseId("different-authority");
                        return identityProviderService.update(provider);
                    }
                });
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> edit.get(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                releaseAccount.countDown();
                assertThat(bind.get(15, java.util.concurrent.TimeUnit.SECONDS).path("status").asText()).isEqualTo("succeeded");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> edit.get(15, java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                        .hasRootCauseMessage("identity scope cannot change while bindings exist");
                assertThat(jdbcTemplate.queryForObject("select external_enterprise_id from iam_identity_provider where id = ?",
                        String.class, providerId)).isNull();
            } finally {
                releaseAccount.countDown();
            }
            hold.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void successfulAdmissionsLeaveProviderAndPolicyVersionsUnchanged() {
        String username = "version_user";
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("federation-it", "Test"));
             var tenant = TenantContext.use(TENANT)) {
            createUser(username);
        }
        Integer providerVersion = jdbcTemplate.queryForObject("select version from iam_identity_provider where id = ?", Integer.class, BlockingProtocol.TYPE);
        Integer policyVersion = jdbcTemplate.queryForObject("select version from iam_tenant_identity_policy where tenant_id = ? and provider_id = ?", Integer.class, TENANT, BlockingProtocol.TYPE);
        String token = passwordLogin(username);
        JsonNode bind = post("/iam.auth/external/" + BlockingProtocol.TYPE + "/bind", Map.of(), token).getBody();
        assertThat(completeBlocking(bind, "external-version").path("status").asText()).isEqualTo("succeeded");
        JsonNode login = post("/iam.auth/external/" + BlockingProtocol.TYPE + "/login", Map.of("tenantId", TENANT), null).getBody();
        assertThat(completeBlocking(login, "external-version").path("status").asText()).isEqualTo("succeeded");
        assertThat(jdbcTemplate.queryForObject("select version from iam_identity_provider where id = ?", Integer.class, BlockingProtocol.TYPE)).isEqualTo(providerVersion);
        assertThat(jdbcTemplate.queryForObject("select version from iam_tenant_identity_policy where tenant_id = ? and provider_id = ?", Integer.class, TENANT, BlockingProtocol.TYPE)).isEqualTo(policyVersion);
    }

    @Test
    void finalAdmissionSerializesWithUnbindPolicyDisableSessionRevocationAndAccountDisable() throws Exception {
        for (String mutation : java.util.List.of("unbind", "provider", "policy", "session", "account")) {
            String username = "race_" + mutation;
            String externalId = "external_" + mutation;
            try (var actor = CurrentUserContext.use(CurrentUser.systemUser("federation-it", "Test"));
                 var tenant = TenantContext.use(TENANT)) {
                createUser(username);
            }
            String token = passwordLogin(username);
            String userId = jdbcTemplate.queryForObject("select id from iam_user where tenant_id = ? and username = ?",
                    String.class, TENANT, username);
            JsonNode start;
            if (mutation.equals("unbind")) {
                JsonNode bind = post("/iam.auth/external/" + BlockingProtocol.TYPE + "/bind", Map.of(), token).getBody();
                assertThat(completeBlocking(bind, externalId).path("status").asText()).isEqualTo("succeeded");
                start = post("/iam.auth/external/" + BlockingProtocol.TYPE + "/login", Map.of("tenantId", TENANT), null).getBody();
            } else {
                start = post("/iam.auth/external/" + BlockingProtocol.TYPE + "/bind", Map.of(), token).getBody();
            }
            var mutationWritten = new java.util.concurrent.CountDownLatch(1);
            var commitMutation = new java.util.concurrent.CountDownLatch(1);
            blockingProtocol.arm();
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var completion = executor.submit(() -> completeBlocking(start, externalId));
                assertThat(blockingProtocol.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var change = executor.submit(() -> {
                    try (var actor = CurrentUserContext.use(CurrentUser.systemUser("federation-it", "Test"));
                         var tenant = TenantContext.use(TENANT)) {
                        transactionTemplate.executeWithoutResult(status -> {
                            switch (mutation) {
                                case "unbind" -> externalIdentityService.bindingsOfUser(userId)
                                        .forEach(binding -> externalIdentityService.delete(binding.getId()));
                                case "provider" -> identityProviderService.disable(BlockingProtocol.TYPE);
                                case "policy" -> tenantIdentityPolicyService.disable(tenantIdentityPolicyService
                                        .enabledPolicy(TENANT, BlockingProtocol.TYPE).orElseThrow().getId());
                                case "session" -> userSessionService.revokeUserSessions(userId);
                                case "account" -> userAccountService.disable(userId);
                                default -> throw new AssertionError(mutation);
                            }
                            mutationWritten.countDown();
                            await(commitMutation);
                        });
                    }
                });
                try {
                    assertThat(mutationWritten.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    blockingProtocol.release.countDown();
                    // A plain MVCC read would observe the old enabled/bound/live state and finish here.
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> completion.get(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                } finally {
                    commitMutation.countDown();
                    blockingProtocol.release.countDown();
                }
                change.get(15, java.util.concurrent.TimeUnit.SECONDS);
                JsonNode denied = completion.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(denied.path("status").asText()).as(mutation).isEqualTo("denied");
                assertThat(denied.path("failure").asText()).as(mutation).isEqualTo(switch (mutation) {
                    case "unbind" -> "identityNotBound";
                    case "provider", "policy" -> "policyDisabled";
                    case "session" -> "sessionEnded";
                    case "account" -> "accountUnavailable";
                    default -> throw new AssertionError(mutation);
                });
                assertThat(jdbcTemplate.queryForObject("select count(*) from iam_user_session where user_id = ? and authentication_method like 'external:%'",
                        Integer.class, userId)).isZero();
                if (!mutation.equals("unbind")) {
                    assertThat(jdbcTemplate.queryForObject("select count(*) from iam_external_identity where user_id = ?",
                            Integer.class, userId)).isZero();
                }
            } finally {
                blockingProtocol.disarm();
                try (var actor = CurrentUserContext.use(CurrentUser.systemUser("federation-it", "Test"));
                     var tenant = TenantContext.use(TENANT)) {
                    if (mutation.equals("provider")) identityProviderService.enable(BlockingProtocol.TYPE);
                    if (mutation.equals("policy")) {
                        var policy = tenantIdentityPolicyService.list(Criteria.of().eq("providerId", BlockingProtocol.TYPE), new PageRequest(0, 1)).getFirst();
                        tenantIdentityPolicyService.enable(policy.getId());
                    }
                }
            }
        }
    }

    private JsonNode completeBlocking(JsonNode start, String externalId) {
        return post("/iam.auth/external/" + BlockingProtocol.TYPE + "/complete", Map.of("state", start.path("state").asText(),
                "browserProof", start.path("browserProof").asText(), "parameters", Map.of("code", externalId)), null).getBody();
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("concurrent operation timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
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
        assertThat(page).contains("模拟身份源授权").contains("action=\"http://workbench.test/app/\"")
                .contains(query.get("code_challenge"));
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        // The provider binds the code to the challenge of the request that obtained it.
        return "mock:" + query.get("code_challenge") + ":" + externalUserId;
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
        StatusProtocol statusProtocol() { return new StatusProtocol(); }

        @Bean
        BlockingProtocol blockingProtocol() { return new BlockingProtocol(); }

        @Bean
        ExternalLoginProtocol foreignEnterpriseProtocol() {
            return new ForeignEnterpriseProtocol();
        }
    }

    static final class StatusProtocol implements ExternalLoginProtocol, net.ximatai.muyun.spring.iam.federation.ExternalDirectoryProtocol {
        static final String TYPE = "status_idp";
        volatile String status = "active";
        volatile boolean fail;
        volatile int calls;
        @Override public void validateConfiguration(Map<String, String> configuration) { }
        @Override public java.util.Set<String> identityScopeConfigurationKeys() { return java.util.Set.of(); }
        @Override public String providerType() { return TYPE; }
        @Override public URI authorizationUri(IdentityProviderDescriptor provider, ExternalAuthorizationContext context) {
            return URI.create("https://idp.test/status?state=" + encode(context.state()));
        }
        @Override public VerifiedExternalIdentity verify(IdentityProviderDescriptor provider, Map<String, String> parameters,
                                                        ExternalAuthorizationContext context) {
            return new VerifiedExternalIdentity("status-corp", parameters.get("code"), null, java.time.Instant.now());
        }
        @Override public Person person(IdentityProviderDescriptor provider, String id) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            if (fail) throw new IllegalStateException("status unavailable");
            return new Person(id, id, null, "root", java.util.List.of("root"), status, null, null);
        }
        @Override public Department department(IdentityProviderDescriptor provider, String id) { throw new UnsupportedOperationException(); }
        @Override public Page<Department> departments(IdentityProviderDescriptor provider, String root, String cursor, int limit) { throw new UnsupportedOperationException(); }
        @Override public Page<String> departmentPeople(IdentityProviderDescriptor provider, String department, String cursor, int limit) { throw new UnsupportedOperationException(); }
    }

    static final class BlockingProtocol implements ExternalLoginProtocol {
        static final String TYPE = "blocking_idp";
        volatile java.util.concurrent.CountDownLatch entered;
        volatile java.util.concurrent.CountDownLatch release;
        void arm() { entered = new java.util.concurrent.CountDownLatch(1); release = new java.util.concurrent.CountDownLatch(1); }
        void disarm() { entered = null; release = null; }
        @Override public String providerType() { return TYPE; }
        @Override public URI authorizationUri(IdentityProviderDescriptor provider, ExternalAuthorizationContext context) {
            return URI.create("https://idp.test/authorize?state=" + encode(context.state()));
        }
        @Override public VerifiedExternalIdentity verify(IdentityProviderDescriptor provider, Map<String, String> parameters,
                                                        ExternalAuthorizationContext context) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var current = entered;
            if (current != null) { current.countDown(); await(release); }
            return new VerifiedExternalIdentity("test-enterprise", parameters.get("code"), null, java.time.Instant.now());
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
