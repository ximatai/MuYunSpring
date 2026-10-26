package net.ximatai.muyun.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.iam.user.PasswordHashingService;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationDao;
import net.ximatai.muyun.spring.iam.position.Position;
import net.ximatai.muyun.spring.iam.position.PositionDao;
import net.ximatai.muyun.spring.iam.role.Role;
import net.ximatai.muyun.spring.iam.role.RoleDao;
import net.ximatai.muyun.spring.iam.role.RoleAction;
import net.ximatai.muyun.spring.iam.role.RoleActionDao;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.platform.job.JobService;
import net.ximatai.muyun.spring.starter.configuration.platform.JobNodeExecutor;
import net.ximatai.muyun.spring.iam.federation.MockExternalDirectoryProtocol;


import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.federation.*;
import net.ximatai.muyun.spring.iam.organization.*;
import net.ximatai.muyun.spring.iam.department.*;
import net.ximatai.muyun.spring.iam.position.*;
import net.ximatai.muyun.spring.iam.role.*;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.iam.federation.ExternalAuthorizationContext;
import net.ximatai.muyun.spring.iam.federation.ExternalDirectoryProtocol;
import net.ximatai.muyun.spring.iam.federation.ExternalIdentity;
import net.ximatai.muyun.spring.iam.federation.ExternalIdentityBindingSource;
import net.ximatai.muyun.spring.iam.federation.ExternalIdentityService;
import net.ximatai.muyun.spring.iam.federation.ExternalLoginProtocol;
import net.ximatai.muyun.spring.iam.federation.IdentityProvider;
import net.ximatai.muyun.spring.iam.federation.IdentityProviderDescriptor;
import net.ximatai.muyun.spring.iam.federation.IdentityProviderService;
import net.ximatai.muyun.spring.iam.federation.MockExternalLoginProtocol;
import net.ximatai.muyun.spring.iam.federation.TenantIdentityPolicy;
import net.ximatai.muyun.spring.iam.federation.TenantIdentityPolicyService;
import net.ximatai.muyun.spring.iam.federation.VerifiedExternalIdentity;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.PasswordStatus;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.iam.user.UserSession;
import net.ximatai.muyun.spring.iam.user.UserSessionDao;
import net.ximatai.muyun.spring.iam.user.UserSessionService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ExternalIdentityService externalIdentityService;
    @Autowired UserSessionService userSessionService;
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
        // Drive directory jobs explicitly so the HTTP regression has deterministic transaction boundaries.
        applicationContext.getBean(JobNodeExecutor.class).stop();
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
                for (String providerId : List.of(PROVIDER, FOREIGN_PROVIDER, SECOND_PROVIDER, BlockingProtocol.TYPE)) {
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
                var binding = new ExternalIdentity();
                binding.setProviderId(providerId);
                binding.setIdentityNamespace("status-test");
                binding.setExternalEnterpriseId("status-corp");
                binding.setExternalUserId("source-status-user");
                binding.setUserId(userId);
                binding.setBindingSource(ExternalIdentityBindingSource.DIRECTORY_SYNC);
                binding.setBoundAt(Instant.now().minusSeconds(172800));
                binding.setSourceStatus("active");
                externalIdentityService.insert(binding);
                bindingId = binding.getId();
            }
        }
        statusProtocol.fail = false;
        statusProtocol.status = "active";
        statusProtocol.omitEnterprise = true;
        statusProtocol.singleEnterprise = false;
        int initialCalls = statusProtocol.calls;
        assertThat(statusCompletion().path("failure").asText()).isEqualTo("enterpriseMismatch");
        statusProtocol.singleEnterprise = true;
        jdbcTemplate.update("update iam_identity_provider set external_enterprise_id = null where id = ?", providerId);
        assertThat(statusCompletion().path("failure").asText()).isEqualTo("enterpriseMismatch");
        jdbcTemplate.update("update iam_identity_provider set external_enterprise_id = 'status-corp' where id = ?", providerId);
        statusProtocol.lookupSupported = false;
        // The platform default allows a missed daily sync: a 48-hour source is still fresh.
        assertThat(statusCompletion().path("status").asText()).isEqualTo("succeeded");
        assertThat(statusProtocol.calls).isEqualTo(initialCalls);
        jdbcTemplate.update("update iam_external_identity set bound_at = current_timestamp - interval '4 days' where id = ?", bindingId);
        assertThat(statusCompletion().path("failure").asText()).isEqualTo("providerUnavailable");
        assertThat(statusProtocol.calls).isEqualTo(initialCalls);
        statusProtocol.lookupSupported = true;
        var success = statusCompletion();
        assertThat(success.path("status").asText()).isEqualTo("succeeded");
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(externalIdentityService.select(bindingId).getSourceCheckedAt()).isNotNull();
            assertThat(userSessionDao.query(Criteria.of().eq("userId", userId), new PageRequest(0, 20)))
                    .anyMatch(session -> session.getRevokedAt() == null);
        }
        jdbcTemplate.update("update iam_external_identity set source_checked_at = current_timestamp - interval '4 days' where id = ?", bindingId);
        statusProtocol.fail = true;
        assertThat(statusCompletion().path("failure").asText()).isEqualTo("providerUnavailable");
        statusProtocol.fail = false;
        statusProtocol.status = "unknown";
        assertThat(statusCompletion().path("failure").asText()).isEqualTo("providerRejected");
        try (var tenant = TenantContext.use(TENANT)) {
            var unchanged = externalIdentityService.select(bindingId);
            assertThat(unchanged.getSourceStatus()).isEqualTo("active");
            assertThat(unchanged.getSourceCheckedAt()).isBefore(Instant.now().minusSeconds(259200));
            assertThat(userSessionDao.query(Criteria.of().eq("userId", userId), new PageRequest(0, 20)))
                    .anyMatch(session -> session.getRevokedAt() == null);
        }
        statusProtocol.status = "departed";
        assertThat(statusCompletion().path("status").asText()).isEqualTo("denied");
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(externalIdentityService.select(bindingId).getSourceStatus()).isEqualTo("departed");
            assertThat(userAccountService.select(userId).getEnabled()).isTrue();
            assertThat(userSessionDao.query(Criteria.of().eq("userId", userId), new PageRequest(0, 20)))
                    .allMatch(session -> session.getRevokedAt() != null);
        }
        statusProtocol.omitEnterprise = false;
        statusProtocol.singleEnterprise = false;
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

    @Test
    void oldSelfBindingsRemainUsableWithoutDirectoryManagement() throws Exception {
        blockingProtocol.disarm();
        for (String providerId : List.of(BlockingProtocol.TYPE, PROVIDER)) {
            String username = "old-self-" + providerId;
            try (var current = CurrentUserContext.use(CurrentUser.systemUser("self-it", "self-it"));
                 var tenant = TenantContext.use(TENANT)) {
                createUser(username);
            }
            String token = passwordLogin(username);
            var bind = post("/iam.auth/external/" + providerId + "/bind", Map.of(), token).getBody();
            JsonNode bound = providerId.equals(PROVIDER) ? completeFlow(bind, username) : completeBlocking(bind, username);
            assertThat(bound.path("status").asText()).isEqualTo("succeeded");
            String bindingId = bound.path("binding").path("id").asText();
            jdbcTemplate.update("update iam_external_identity set bound_at = current_timestamp - interval '2 days' where id = ?", bindingId);
            var login = post("/iam.auth/external/" + providerId + "/login", Map.of("tenantId", TENANT), null).getBody();
            JsonNode result = providerId.equals(PROVIDER) ? completeFlow(login, username) : completeBlocking(login, username);
            assertThat(result.path("status").asText()).isEqualTo("succeeded");
            try (var tenant = TenantContext.use(TENANT)) {
                var binding = externalIdentityService.select(bindingId);
                assertThat(binding.getBoundAt()).isBefore(Instant.now().minusSeconds(86400));
                assertThat(binding.getSourceCheckedAt()).isNull();
            }
        }
    }

    @Test
    void explicitRebindingClearsOnlyItsBlockAndFailedBindingPreservesIt() throws Exception {
        try (var current = CurrentUserContext.use(CurrentUser.systemUser("rebind-it", "rebind-it"));
             var tenant = TenantContext.use(TENANT)) {
            createUser("rebind-user");
            createUser("rebind-occupied");
        }
        String externalId = "explicit-rebind";
        String token = passwordLogin("rebind-user");
        var bound = completeFlow(post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(), token), externalId);
        assertThat(bound.path("status").asText()).isEqualTo("succeeded");
        String bindingId = bound.path("binding").path("id").asText();
        assertThat(post("/iam.auth/external/identities/" + bindingId + "/delete", Map.of(), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(externalIdentityService.isAutoRebindBlocked(PROVIDER, externalId)).isTrue();
        }
        String occupiedToken = passwordLogin("rebind-occupied");
        var failedStart = post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(), occupiedToken);
        var other = completeFlow(post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(), occupiedToken), "other-rebind");
        assertThat(other.path("status").asText()).isEqualTo("succeeded");
        var failed = completeFlow(failedStart, externalId);
        assertThat(failed.path("failure").asText()).isEqualTo("alreadyBound");
        assertThat(post("/iam.auth/external/identities/" + other.path("binding").path("id").asText() + "/delete",
                Map.of(), occupiedToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(externalIdentityService.isAutoRebindBlocked(PROVIDER, externalId)).isTrue();
            assertThat(externalIdentityService.isAutoRebindBlocked(PROVIDER, "other-rebind")).isTrue();
        }
        token = passwordLogin("rebind-user");
        var rebound = completeFlow(post("/iam.auth/external/" + PROVIDER + "/bind", Map.of(), token), externalId);
        assertThat(rebound.path("status").asText()).isEqualTo("succeeded");
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(externalIdentityService.isAutoRebindBlocked(PROVIDER, externalId)).isFalse();
            assertThat(externalIdentityService.isAutoRebindBlocked(PROVIDER, "other-rebind")).isTrue();
        }
        assertThat(completeFlow(startLogin(), externalId).path("status").asText()).isEqualTo("succeeded");
        assertThat(post("/iam.auth/external/identities/" + rebound.path("binding").path("id").asText() + "/delete",
                Map.of(), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(externalIdentityService.isAutoRebindBlocked(PROVIDER, externalId)).isTrue();
        }
    }

    @Test
    void directoryUnbindPasswordLoginAndExplicitRebindKeepSourcePermissionsUnderSyncControl() throws Exception {
        String providerId = "audit_directory";
        var json = new ObjectMapper();
        var organizationRows = applicationContext.getBean(OrganizationDao.class);
        var positionRows = applicationContext.getBean(PositionDao.class);
        var roleRows = applicationContext.getBean(RoleDao.class);
        var roleActions = applicationContext.getBean(RoleActionDao.class);
        var roles = applicationContext.getBean(RoleService.class);
        var jobs = applicationContext.getBean(JobService.class);
        TenantIdentityPolicy policy;
        String userId;
        String username;
        String bindingId;
        var sourceRole = new Role();
        try (var current = CurrentUserContext.use(CurrentUser.systemUser("audit-it", "audit-it"));
             var tenant = TenantContext.use(TENANT)) {
            var organization = new Organization();
            organization.setCode("audit"); organization.setTitle("Audit");
            insertFixture(organizationRows, organization);
            var position = new Position();
            position.setCode("audit"); position.setTitle("Audit"); position.setCategoryId("audit");
            insertFixture(positionRows, position);
            sourceRole.setTitle("Source access"); sourceRole.setOwnerScopeKey("tenant:");
            insertFixture(roleRows, sourceRole);
            var permission = new RoleAction();
            permission.setRoleId(sourceRole.getId()); permission.setModuleAlias("audit.resource"); permission.setActionCode("view"); // query inherits view
            insertFixture(roleActions, permission);
            var provider = new IdentityProvider();
            provider.setId(providerId); provider.setTitle("Audit directory"); provider.setProviderType("mock");
            provider.setExternalEnterpriseId("audit-corp"); provider.setIdentityNamespace("audit-directory");
            provider.setConfiguration(directoryFixture("active"));
            try (var global = TenantContext.system("create audit provider")) { identityProviderService.insert(provider); }
            policy = new TenantIdentityPolicy(); policy.setProviderId(providerId); policy.setEnabled(true);
            policy.setSyncEnabled(true); policy.setSyncScopeConfirmed(true); policy.setSyncPositionId(position.getId());
            policy.setSyncRoleId(sourceRole.getId()); policy.setSyncExpression("0 0 * * * *");
            policy.setSyncRoots(json.writeValueAsString(List.of(Map.of("externalDepartmentId", "team", "organizationId", organization.getId()))));
            tenantIdentityPolicyService.insert(policy);
            tenantIdentityPolicyService.synchronize(policy.getId(), false);
        }
        assertThat(jobs.runOne("directory-sync", "audit-node")).isTrue();
        try (var tenant = TenantContext.use(TENANT)) {
            var binding = externalIdentityService.list(Criteria.of().eq("providerId", providerId), new PageRequest(0, 1)).getFirst();
            bindingId = binding.getId(); userId = binding.getUserId();
            username = userAccountService.select(userId).getUsername();
            jdbcTemplate.update("update iam_user set password_hash=?, password_status='normal', password_changed_at=current_timestamp where id=?",
                    applicationContext.getBean(PasswordHashingService.class).hash(PASSWORD), userId);
            assertThat(roles.hasActionPermission(userId, "audit.resource", "query")).isTrue();
        }
        String token = passwordLogin(username);
        assertThat(post("/iam.auth/external/identities/" + bindingId + "/delete", Map.of(), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(roles.hasActionPermission(userId, "audit.resource", "query")).isFalse();
            assertThat(userSessionDao.query(Criteria.of().eq("userId", userId), new PageRequest(0, 100)))
                    .allMatch(session -> session.getRevokedAt() != null);
        }
        // A departed observation after physical deletion cannot leave stale access behind.
        try (var current = CurrentUserContext.use(CurrentUser.systemUser("audit-it", "audit-it"));
             var tenant = TenantContext.use(TENANT)) {
            try (var global = TenantContext.system("change audit directory")) {
                var provider = identityProviderService.select(providerId);
                provider.setConfiguration(directoryFixture("departed")); identityProviderService.update(provider);
            }
            tenantIdentityPolicyService.synchronize(policy.getId(), false);
        }
        assertThat(jobs.runOne("directory-sync", "audit-node")).isTrue();
        token = passwordLogin(username);
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(roles.hasActionPermission(userId, "audit.resource", "query")).isFalse();
        }
        // Explicit consent restores management, while fresh source facts determine actual access.
        var rebound = completeFlow(providerId, post("/iam.auth/external/" + providerId + "/bind", Map.of(), token), "audit-alice");
        assertThat(rebound.path("status").asText()).isEqualTo("succeeded");
        try (var tenant = TenantContext.use(TENANT)) {
            assertThat(externalIdentityService.select(rebound.path("binding").path("id").asText()).getBindingSource())
                    .isEqualTo(ExternalIdentityBindingSource.DIRECTORY_SYNC);
            assertThat(roles.hasActionPermission(userId, "audit.resource", "query")).isFalse();
        }
        jdbcTemplate.update("update iam_external_identity set bound_at=current_timestamp-interval '4 days' where id=?",
                rebound.path("binding").path("id").asText());
        assertThat(completeFlow(providerId, post("/iam.auth/external/" + providerId + "/login", Map.of("tenantId", TENANT), null),
                "audit-alice").path("failure").asText()).isEqualTo("accountUnavailable");
        for (String status : List.of("departed", "active", "absent")) {
            try (var current = CurrentUserContext.use(CurrentUser.systemUser("audit-it", "audit-it"));
                 var tenant = TenantContext.use(TENANT)) {
                try (var global = TenantContext.system("change audit directory")) {
                    var provider = identityProviderService.select(providerId);
                    provider.setConfiguration(directoryFixture(status)); identityProviderService.update(provider);
                }
                tenantIdentityPolicyService.synchronize(policy.getId(), false);
            }
            assertThat(jobs.runOne("directory-sync", "audit-node")).isTrue();
            passwordLogin(username);
            try (var tenant = TenantContext.use(TENANT)) {
                assertThat(roles.hasActionPermission(userId, "audit.resource", "query")).isEqualTo(status.equals("active"));
                assertThat(jdbcTemplate.queryForObject("select count(*) from iam_employment_role_grant where tenant_id=? and source='directory' and role_id=?",
                        Long.class, TENANT, sourceRole.getId())).isEqualTo(status.equals("active") ? 1L : 0L);
            }
        }
        token = passwordLogin(username);
        assertThat(post("/iam.auth/external/identities/" + rebound.path("binding").path("id").asText() + "/delete",
                Map.of(), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        try (var current = CurrentUserContext.use(CurrentUser.systemUser("audit-it", "audit-it"));
             var tenant = TenantContext.use(TENANT)) { createUser("audit-other-account"); }
        var otherBinding = completeFlow(providerId, post("/iam.auth/external/" + providerId + "/bind", Map.of(), passwordLogin("audit-other-account")), "audit-alice");
        assertThat(otherBinding.path("status").asText()).isEqualTo("succeeded");
        try (var tenant = TenantContext.use(TENANT)) {
            var other = externalIdentityService.select(otherBinding.path("binding").path("id").asText());
            assertThat(other.getBindingSource()).isEqualTo(ExternalIdentityBindingSource.SELF_BIND);
            assertThat(roles.hasActionPermission(other.getUserId(), "audit.resource", "query")).isFalse();
            assertThat(roles.hasActionPermission(userId, "audit.resource", "query")).isFalse();
        }
    }

    @Test
    void staleLoginObservationCannotRefreshARecreatedUnboundIdentity() {
        String providerId = "rebind_race";
        String username = "rebind-race-user";
        String userId;
        String oldId;
        try (var current = CurrentUserContext.use(CurrentUser.systemUser("audit-it", "audit-it"));
             var global = TenantContext.system("seed rebind race")) {
            var provider = new IdentityProvider();
            provider.setId(providerId); provider.setTitle("Rebind race"); provider.setProviderType(StatusProtocol.TYPE);
            provider.setExternalEnterpriseId("status-corp"); provider.setIdentityNamespace("rebind-race");
            identityProviderService.insert(provider);
            try (var tenant = TenantContext.use(TENANT)) {
                var policy = new TenantIdentityPolicy(); policy.setProviderId(providerId);
                tenantIdentityPolicyService.insert(policy);
                createUser(username);
                userId = userAccountService.list(Criteria.of().eq("username", username), new PageRequest(0, 1)).getFirst().getId();
                var binding = new ExternalIdentity();
                binding.setUserId(userId); binding.setProviderId(providerId); binding.setIdentityNamespace("rebind-race");
                binding.setExternalEnterpriseId("status-corp"); binding.setExternalUserId(username);
                binding.setBindingSource(ExternalIdentityBindingSource.DIRECTORY_SYNC);
                binding.setSourceStatus("active"); binding.setBoundAt(Instant.now().minusSeconds(4 * 86400));
                externalIdentityService.insert(binding); oldId = binding.getId();
            }
        }
        String token = passwordLogin(username);
        statusProtocol.status = "active"; statusProtocol.fail = false; statusProtocol.lookupSupported = true;
        statusProtocol.omitEnterprise = false;
        statusProtocol.lookupHook = () -> {
            statusProtocol.lookupHook = () -> {};
            assertThat(post("/iam.auth/external/identities/" + oldId + "/delete", Map.of(), token).getStatusCode()).isEqualTo(HttpStatus.OK);
            var rebound = statusFlow(providerId, username, passwordLogin(username));
            assertThat(rebound.path("status").asText()).isEqualTo("succeeded");
            // The new row has version zero just as the deleted row did.
            try (var tenant = TenantContext.use(TENANT)) {
                var binding = externalIdentityService.select(rebound.path("binding").path("id").asText());
                assertThat(binding.getId()).isNotEqualTo(oldId);
                assertThat(binding.getVersion()).isZero();
                assertThat(binding.getSourceStatus()).isEqualTo("unbound");
            }
        };
        try {
            assertThat(statusFlow(providerId, username, null).path("failure").asText()).isEqualTo("accountUnavailable");
            try (var tenant = TenantContext.use(TENANT)) {
                assertThat(externalIdentityService.bindingsOfUser(userId)).singleElement()
                        .satisfies(binding -> assertThat(binding.getSourceStatus()).isEqualTo("unbound"));
            }
        } finally { statusProtocol.lookupHook = () -> {}; }
    }

    private JsonNode statusFlow(String providerId, String externalId, String token) {
        var start = post("/iam.auth/external/" + providerId + (token == null ? "/login" : "/bind"),
                token == null ? Map.of("tenantId", TENANT) : Map.of(), token).getBody();
        String state = start.path("state").asText();
        return post("/iam.auth/external/" + providerId + "/complete", Map.of("state", state,
                "browserProof", start.path("browserProof").asText(), "parameters", Map.of("state", state, "code", externalId)), null).getBody();
    }

    private String directoryFixture(String status) throws Exception {
        var departments = List.of(new ExternalDirectoryProtocol.Department("company", null, "Company"),
                new ExternalDirectoryProtocol.Department("team", "company", "Team"));
        var people = status.equals("absent") ? List.<ExternalDirectoryProtocol.Person>of() : List.of(
                new ExternalDirectoryProtocol.Person("audit-alice", "Audit Alice", "audit-001", "team", List.of("team"), status, null, null));
        var json = new ObjectMapper();
        return json.writeValueAsString(Map.of("directory", json.writeValueAsString(
                new MockExternalDirectoryProtocol.Data(departments, people, List.of()))));
    }

    private <T extends StandardEntity> void insertFixture(
            BaseDao<T, String> rows, T row) {
        row.setTenantId(TENANT);
        EntityLifecycle.prepareInsert(row, Instant.now());
        rows.insert(row);
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
        for (String providerId : List.of(PROVIDER, SECOND_PROVIDER)) {
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
                "/iam.auth/external/{providerId}/confirm", "/iam.auth/external/mock/authorize");
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
        for (String mutation : List.of("unbind", "provider", "policy", "session", "account")) {
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


    @Autowired OrganizationService organizations;
    @Autowired DepartmentService departments;
    @Autowired PositionCategoryService categories;
    @Autowired PositionService positions;
    @Autowired RoleService roles;
    @Autowired DirectoryDepartmentMappingDao mappings;
    @Autowired JoinApplicationService applications;
    @Autowired net.ximatai.muyun.spring.platform.job.JobService jobs;
    @Autowired net.ximatai.muyun.spring.starter.configuration.platform.JobNodeExecutor jobExecutor;
    @Autowired net.ximatai.muyun.spring.platform.job.JobRunDao jobRuns;

    private record ProvisioningFixture(String provider, String policy, String role) { }

    private ProvisioningFixture provisioningFixture(String id, ProvisioningMode mode) {
        statusProtocol.status = "active"; statusProtocol.fail = false;
        statusProtocol.lookupSupported = true; statusProtocol.omitEnterprise = false;
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
             var tenant = TenantContext.use(TENANT)) {
            var provider = new IdentityProvider();
            provider.setId(id); provider.setTitle(id); provider.setProviderType(StatusProtocol.TYPE);
            provider.setExternalEnterpriseId("status-corp"); provider.setIdentityNamespace(id);
            try (var system = TenantContext.system("provisioning fixture provider")) { identityProviderService.insert(provider); }
            var organization = new Organization(); organization.setCode(id); organization.setTitle(id);
            organizations.insert(organization);
            var category = new PositionCategory(); category.setCode(id); category.setTitle(id); categories.insert(category);
            var position = new Position(); position.setCode(id); position.setTitle(id); position.setCategoryId(category.getId()); positions.insert(position);
            var role = new Role(); role.setTitle(id); roles.insert(role);
            var policy = new TenantIdentityPolicy(); policy.setProviderId(id); policy.setProvisioningMode(mode);
            policy.setAdmissionRoots("[\"root\"]"); policy.setSyncPositionId(position.getId()); policy.setSyncRoleId(role.getId());
            policy.setSyncScopeConfirmed(true);
            policy.setSyncRoots("[{\"externalDepartmentId\":\"root\",\"organizationId\":\"" + organization.getId() + "\"}]");
            tenantIdentityPolicyService.insert(policy);
            jobExecutor.stop();
            statusProtocol.directoryPeople = List.of();
            String baseline = tenantIdentityPolicyService.synchronize(policy.getId(), false);
            assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "provisioning-it")).isTrue();
            assertThat(jobRuns.findById(baseline).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
            return new ProvisioningFixture(id, policy.getId(), role.getId());
        }
    }

    private JsonNode provisioningScan(String provider, String person) {
        var start = post("/iam.auth/external/" + provider + "/login", Map.of("tenantId", TENANT), null).getBody();
        var result = post("/iam.auth/external/" + provider + "/complete", Map.of("state", start.path("state").asText(),
                "browserProof", start.path("browserProof").asText(), "parameters", Map.of("code", person)), null).getBody();
        var combined = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        combined.set("start", start); combined.set("result", result); return combined;
    }

    private JsonNode confirmProvisioning(String provider, JsonNode start) {
        return post("/iam.auth/external/" + provider + "/confirm", Map.of("state", start.path("state").asText(),
                "browserProof", start.path("browserProof").asText(), "parameters", Map.of("tenantId", "forged", "externalUserId", "forged")), null).getBody();
    }

    @Test
    void autoProvisioningConfirmsAtomicallyAndConcurrentConfirmationsCreateOneAccount() throws Exception {
        var fixture = provisioningFixture("auto_provision", ProvisioningMode.AUTO);
        var scan = provisioningScan(fixture.provider(), "new-auto");
        assertThat(scan.path("result").path("status").asText()).isEqualTo("confirmationRequired");
        assertThat(scan.path("result").path("login").isNull()).isTrue();
        assertThat(scan.path("result").path("confirmation").path("placements").toString()).contains("auto_provision / Root");
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isZero();
        var second = provisioningScan(fixture.provider(), "new-auto");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> confirmProvisioning(fixture.provider(), scan.path("start")));
            var repeated = executor.submit(() -> confirmProvisioning(fixture.provider(), scan.path("start")));
            var concurrent = executor.submit(() -> confirmProvisioning(fixture.provider(), second.path("start")));
            var results = List.of(first.get(), repeated.get(), concurrent.get());
            assertThat(results).allSatisfy(result -> assertThat(result.path("status").asText()).isEqualTo("succeeded"));
            assertThat(results.stream().filter(result -> result.path("login").path("token").isTextual()).count()).isEqualTo(2);
        }
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isEqualTo(1);
        String user = jdbcTemplate.queryForObject("select user_id from iam_external_identity where provider_id=?", String.class, fixture.provider());
        assertThat(count("iam_employee_account", "user_id", user)).isEqualTo(1);
        assertThat(count("iam_employee_position", "source_provider_id", fixture.provider())).isEqualTo(1);
        assertThat(count("iam_employment_role_grant", "role_id", fixture.role())).isEqualTo(1);
        var login = provisioningScan(fixture.provider(), "new-auto").path("result");
        assertThat(login.path("login").path("token").asText()).isNotBlank();
    }

    @Test
    void realtimeAdmissionCannotReuseDepartmentMappingsFromAnEarlierAuthority() {
        var fixture = provisioningFixture("authority_provision", ProvisioningMode.AUTO);
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
             var system = TenantContext.system("change unbound identity authority")) {
            var provider = identityProviderService.select(fixture.provider());
            provider.setIdentityNamespace("changed-namespace"); identityProviderService.update(provider);
        }
        assertThat(provisioningScan(fixture.provider(), "new-authority").path("result").path("failure").asText()).isEqualTo("admissionRejected");
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isZero();
    }

    @Test
    void failureWhileGrantingTheDefaultRoleRollsBackTheWholeAccountAggregate() {
        var fixture = provisioningFixture("rollback_provision", ProvisioningMode.AUTO);
        var scan = provisioningScan(fixture.provider(), "rollback-person");
        assertThat(scan.path("result").path("status").asText()).isEqualTo("confirmationRequired");
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
             var tenant = TenantContext.use(TENANT)) { roles.disable(fixture.role()); }
        assertThat(confirmProvisioning(fixture.provider(), scan.path("start")).path("status").asText()).isNotEqualTo("succeeded");
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isZero();
        String organization = jdbcTemplate.queryForObject("select organization_id from iam_directory_department_mapping where provider_id=?",
                String.class, fixture.provider());
        assertThat(count("iam_employee", "organization_id", organization)).isZero();
        assertThat(count("iam_employee_position", "source_provider_id", fixture.provider())).isZero();
    }

    @Test
    void authoritativeAdmissionRejectsEmptyOutsideUnknownAndPolicyChangesBeforeConfirmation() {
        var fixture = provisioningFixture("admission_provision", ProvisioningMode.AUTO);
        var scan = provisioningScan(fixture.provider(), "policy-change");
        assertThat(scan.path("result").path("status").asText()).isEqualTo("confirmationRequired");
        jdbcTemplate.update("update iam_tenant_identity_policy set admission_roots='[\"outside\"]' where id=?", fixture.policy());
        assertThat(confirmProvisioning(fixture.provider(), scan.path("start")).path("status").asText()).isNotEqualTo("succeeded");
        assertThat(provisioningScan(fixture.provider(), "outside").path("result").path("failure").asText()).isEqualTo("admissionRejected");
        jdbcTemplate.update("update iam_tenant_identity_policy set admission_roots='[]' where id=?", fixture.policy());
        assertThat(provisioningScan(fixture.provider(), "empty").path("result").path("failure").asText()).isEqualTo("admissionRejected");
        jdbcTemplate.update("update iam_tenant_identity_policy set admission_roots='[\"root\"]' where id=?", fixture.policy());
        statusProtocol.status = "unknown";
        assertThat(provisioningScan(fixture.provider(), "unknown").path("result").path("failure").asText()).isEqualTo("admissionRejected");
        statusProtocol.status = "active"; statusProtocol.lookupSupported = false;
        assertThat(provisioningScan(fixture.provider(), "no-evidence").path("result").path("failure").asText()).isEqualTo("admissionRejected");
        statusProtocol.lookupSupported = true;
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isZero();
    }

    @Test
    void reviewApplicationApprovesUsingSameWriterAndRejectsPersistentlyAcrossPolicyChanges() {
        var fixture = provisioningFixture("review_provision", ProvisioningMode.REVIEW);
        var scan = provisioningScan(fixture.provider(), "review-approved").path("result");
        assertThat(scan.path("status").asText()).isEqualTo("reviewPending");
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isZero();
        assertThat(provisioningScan(fixture.provider(), "review-approved").path("result").path("status").asText()).isEqualTo("reviewPending");
        assertThat(count("iam_join_application", "provider_id", fixture.provider())).isEqualTo(1);
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
             var tenant = TenantContext.use(TENANT)) {
            statusProtocol.directoryPeople = List.of("review-approved");
            String run = tenantIdentityPolicyService.synchronize(fixture.policy(), false);
            assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "provisioning-it")).isTrue();
            assertThat(jobRuns.findById(run).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
            assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isZero();
        }
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-reviewer", "Reviewer"));
             var tenant = TenantContext.use(TENANT)) {
            var row = applications.list(Criteria.of().eq("providerId", fixture.provider()), new PageRequest(0, 10)).getFirst();
            applications.review(row.getId(), true, "Verified");
            applications.review(row.getId(), true, "Repeated approval");
        }
        assertThat(provisioningScan(fixture.provider(), "review-approved").path("result").path("login").path("token").asText()).isNotBlank();
        assertThat(provisioningScan(fixture.provider(), "review-rejected").path("result").path("status").asText()).isEqualTo("reviewPending");
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-reviewer", "Reviewer"));
             var tenant = TenantContext.use(TENANT)) {
            var row = applications.list(Criteria.of().eq("externalUserId", "review-rejected"), new PageRequest(0, 10)).getFirst();
            applications.review(row.getId(), false, "Not admitted");
        }
        assertThat(provisioningScan(fixture.provider(), "review-rejected").path("result").path("login").isNull()).isTrue();
        jdbcTemplate.update("update iam_tenant_identity_policy set provisioning_mode='auto' where id=?", fixture.policy());
        assertThat(provisioningScan(fixture.provider(), "review-rejected").path("result").path("status").asText()).isEqualTo("reviewRejected");
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select reviewer_id from iam_join_application where provider_id=? and status='rejected'",
                String.class, fixture.provider())).isEqualTo("provisioning-reviewer");
    }

    @Test
    void directoryOnlyProvisioningRequiresFreshCompleteObservationsAndRejectsNewerUnknown() {
        var fixture = provisioningFixture("cached_provision", ProvisioningMode.AUTO);
        jobExecutor.stop();
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
             var tenant = TenantContext.use(TENANT)) {
            var mapping = mappings.query(Criteria.of().eq("tenantId", TENANT).eq("providerId", fixture.provider()), new PageRequest(0, 1)).getFirst();
            var policy = tenantIdentityPolicyService.select(fixture.policy());
            policy.setSyncRoots("[{\"externalDepartmentId\":\"root\",\"organizationId\":\"" + mapping.getOrganizationId() + "\"}]");
            policy.setSyncScopeConfirmed(true);
            tenantIdentityPolicyService.update(policy);
            statusProtocol.directoryPeople = List.of();
            String baseline = tenantIdentityPolicyService.synchronize(fixture.policy(), false);
            assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "provisioning-it")).isTrue();
            assertThat(jobRuns.findById(baseline).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
            statusProtocol.directoryPeople = List.of("cached-active", "cached-stale", "cached-unknown");
            String preview = tenantIdentityPolicyService.synchronize(fixture.policy(), true);
            assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "provisioning-it")).isTrue();
            assertThat(jobRuns.findById(preview).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
            statusProtocol.lookupSupported = false;
            var scan = provisioningScan(fixture.provider(), "cached-active");
            assertThat(scan.path("result").path("status").asText()).isEqualTo("confirmationRequired");
            assertThat(confirmProvisioning(fixture.provider(), scan.path("start")).path("login").path("token").asText()).isNotBlank();
            jdbcTemplate.update("update iam_directory_sync_seen set created_at=current_timestamp - interval '4 days' where external_user_id='cached-stale'");
            assertThat(provisioningScan(fixture.provider(), "cached-stale").path("result").path("failure").asText()).isEqualTo("admissionRejected");
            statusProtocol.status = "unknown";
            String unknown = tenantIdentityPolicyService.synchronize(fixture.policy(), true);
            assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "provisioning-it")).isTrue();
            assertThat(jobRuns.findById(unknown).getResultCode()).isEqualTo("DIRECTORY_UNKNOWN_STATUS");
            assertThat(provisioningScan(fixture.provider(), "cached-unknown").path("result").path("failure").asText()).isEqualTo("admissionRejected");
            assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isEqualTo(1);
        } finally {
            statusProtocol.directoryPeople = List.of(); statusProtocol.lookupSupported = true; statusProtocol.status = "active";
        }
    }

    @Test
    void continuingAnOlderPreviewCannotOverrideANewerUnknownObservation() {
        var fixture = provisioningFixture("continued_preview", ProvisioningMode.AUTO);
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
             var tenant = TenantContext.use(TENANT)) {
            statusProtocol.directoryPeople = List.of("continued-person");
            statusProtocol.failAfterPeoplePage = true;
            String older = tenantIdentityPolicyService.synchronize(fixture.policy(), true);
            assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "provisioning-it")).isTrue();
            assertThat(jobRuns.findById(older).getStatus()).isEqualTo("FAILED");
            assertThat(jdbcTemplate.queryForObject("select source_status from iam_directory_sync_seen s join iam_directory_sync_run r on r.id=s.run_id where r.job_run_id=?",
                    String.class, older)).isEqualTo("active");
            statusProtocol.failAfterPeoplePage = false;
            statusProtocol.status = "unknown";
            String newer = tenantIdentityPolicyService.synchronize(fixture.policy(), true);
            assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "provisioning-it")).isTrue();
            assertThat(jobRuns.findById(newer).getResultCode()).isEqualTo("DIRECTORY_UNKNOWN_STATUS");
            var continuation = jobs.retry(older);
            assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "provisioning-it")).isTrue();
            assertThat(jobRuns.findById(continuation.getId()).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
            statusProtocol.lookupSupported = false;
            assertThat(provisioningScan(fixture.provider(), "continued-person").path("result").path("failure").asText())
                    .isEqualTo("admissionRejected");
            assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isZero();
        } finally {
            statusProtocol.failAfterPeoplePage = false;
            statusProtocol.status = "active"; statusProtocol.lookupSupported = true; statusProtocol.directoryPeople = List.of();
        }
    }

    @Test
    void pendingScopeDefaultsDoNotAffectAutoOrReviewProvisioning() {
        for (var mode : List.of(ProvisioningMode.AUTO, ProvisioningMode.REVIEW)) {
            var fixture = provisioningFixture("pending_defaults_" + mode.name().toLowerCase(), mode);
            String activePosition;
            String pendingRole;
            try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
                 var tenant = TenantContext.use(TENANT)) {
                var policy = tenantIdentityPolicyService.select(fixture.policy());
                activePosition = policy.getSyncPositionId();
                String activeScope = policy.getSyncScopeId();
                var position = new Position(); position.setCode(fixture.provider() + "_pending"); position.setTitle("Pending position");
                position.setCategoryId(positions.select(activePosition).getCategoryId()); positions.insert(position);
                var role = new Role(); role.setTitle(fixture.provider() + " pending role"); roles.insert(role); pendingRole = role.getId();
                policy.setSyncRoots("[]"); policy.setSyncScopeConfirmed(true);
                policy.setSyncPositionId(position.getId()); policy.setSyncRoleId(pendingRole);
                tenantIdentityPolicyService.update(policy);
                var saved = tenantIdentityPolicyService.select(fixture.policy());
                assertThat(saved.getSyncScopeId()).isEqualTo(activeScope);
                assertThat(saved.getPendingSyncScopeId()).isNotNull().isNotEqualTo(activeScope);
            }
            var scan = provisioningScan(fixture.provider(), "pending-default-person");
            if (mode == ProvisioningMode.AUTO) {
                assertThat(scan.path("result").path("status").asText()).isEqualTo("confirmationRequired");
                assertThat(confirmProvisioning(fixture.provider(), scan.path("start")).path("status").asText()).isEqualTo("succeeded");
            } else {
                assertThat(scan.path("result").path("status").asText()).isEqualTo("reviewPending");
                try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
                     var tenant = TenantContext.use(TENANT)) {
                    var row = applications.list(Criteria.of().eq("providerId", fixture.provider()), new PageRequest(0, 1)).getFirst();
                    applications.review(row.getId(), true, null);
                }
            }
            assertThat(jdbcTemplate.queryForList("select position_id from iam_employee_position where source_provider_id=?", String.class, fixture.provider()))
                    .containsExactly(activePosition);
            assertThat(count("iam_employment_role_grant", "role_id", fixture.role())).isEqualTo(1);
            assertThat(count("iam_employment_role_grant", "role_id", pendingRole)).isZero();
        }
    }

    @Test
    void reviewEndpointsEnforceActionPermissionsAndTenantIsolationOverHttp() {
        var fixture = provisioningFixture("http_review", ProvisioningMode.REVIEW);
        for (String person : List.of("http-approved", "http-rejected"))
            assertThat(provisioningScan(fixture.provider(), person).path("result").path("status").asText()).isEqualTo("reviewPending");
        String approved = jdbcTemplate.queryForObject("select id from iam_join_application where provider_id=? and external_user_id='http-approved'", String.class, fixture.provider());
        String rejected = jdbcTemplate.queryForObject("select id from iam_join_application where provider_id=? and external_user_id='http-rejected'", String.class, fixture.provider());
        String otherTenant;
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
             var system = TenantContext.system("review HTTP fixtures")) {
            var other = new Tenant(); other.setAlias("http_review_other"); other.setTitle("Other reviewer tenant"); tenantService.insert(other);
            otherTenant = other.getId();
            createReviewUser(TENANT, "http_viewer", List.of());
            createReviewUser(TENANT, "http_approver", List.of("approve"));
            createReviewUser(TENANT, "http_rejector", List.of("reject"));
            createReviewUser(otherTenant, "http_other", List.of("approve", "reject"));
        }
        String viewerToken = passwordLogin("http_viewer");
        String approveToken = passwordLogin("http_approver");
        String rejectToken = passwordLogin("http_rejector");
        var foreignLogin = post("/iam.auth/login", Map.of("tenantId", otherTenant, "username", "http_other", "password", PASSWORD), null);
        assertThat(foreignLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        String foreignToken = foreignLogin.getBody().path("token").asText();
        for (String action : List.of("approve", "reject")) {
            String path = "/iam.join_application/" + (action.equals("approve") ? approved : rejected) + "/" + action;
            assertThat(post(path, Map.of(), null).getStatusCode()).as("anonymous %s", action).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(post(path, Map.of(), viewerToken).getStatusCode()).as("ungranted %s", action).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post(path, Map.of(), action.equals("approve") ? rejectToken : approveToken).getStatusCode())
                    .as("wrong action %s", action).isEqualTo(HttpStatus.FORBIDDEN);
            var foreign = post(path, Map.of(), foreignToken);
            assertThat(foreign.getStatusCode().is4xxClientError()).as("foreign %s: %s", action, foreign.getBody()).isTrue();
        }
        assertThat(jdbcTemplate.queryForList("select status from iam_join_application where provider_id=?", String.class, fixture.provider()))
                .containsOnly("pending");
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isZero();
        assertThat(post("/iam.join_application/" + approved + "/approve", Map.of(), approveToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/iam.join_application/" + rejected + "/reject", Map.of(), rejectToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("select status from iam_join_application where id=?", String.class, approved)).isEqualTo("approved");
        assertThat(jdbcTemplate.queryForObject("select status from iam_join_application where id=?", String.class, rejected)).isEqualTo("rejected");
        assertThat(jdbcTemplate.queryForObject("select reviewer_id from iam_join_application where id=?", String.class, approved))
                .isEqualTo(jdbcTemplate.queryForObject("select id from iam_user where tenant_id=? and username='http_approver'", String.class, TENANT));
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isEqualTo(1);
        assertThat(provisioningScan(fixture.provider(), "http-approved").path("result").path("login").path("token").asText()).isNotBlank();
        assertThat(provisioningScan(fixture.provider(), "http-rejected").path("result").path("status").asText()).isEqualTo("reviewRejected");
    }

    private void createReviewUser(String tenantId, String username, List<String> actions) {
        try (var tenant = TenantContext.use(tenantId)) {
            createUser(username);
            var user = userAccountService.list(Criteria.of().eq("username", username), new PageRequest(0, 1)).getFirst();
            var role = new Role(); role.setTitle(username); role.setAssignmentType(RoleAssignmentType.ACCOUNT);
            roles.insert(role);
            roles.grantAction(role.getId(), JoinApplicationService.MODULE_ALIAS, "view");
            for (String action : actions) roles.grantAction(role.getId(), JoinApplicationService.MODULE_ALIAS, action);
            roles.grantAccountRoleResult(role.getId(), user.getId(), tenantId);
        }
    }

    @Test
    void provisioningDoesNotReuseAnotherTenantsOrganizationFactsOrApplications() {
        var fixture = provisioningFixture("isolation_provision", ProvisioningMode.REVIEW);
        assertThat(provisioningScan(fixture.provider(), "tenant-isolated").path("result").path("status").asText()).isEqualTo("reviewPending");
        String application = jdbcTemplate.queryForObject("select id from iam_join_application where provider_id=?", String.class, fixture.provider());
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("provisioning-it", "Test"));
             var system = TenantContext.system("second tenant fixture")) {
            var second = new Tenant(); second.setAlias("provisioning_other"); second.setTitle("Other"); tenantService.insert(second);
            try (var tenant = TenantContext.use(second.getId())) {
                assertThat(applications.select(application)).isNull();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> applications.review(application, true, null)).isInstanceOf(RuntimeException.class);
                var policy = new TenantIdentityPolicy(); policy.setProviderId(fixture.provider()); policy.setProvisioningMode(ProvisioningMode.AUTO);
                policy.setAdmissionRoots("[\"root\"]"); tenantIdentityPolicyService.insert(policy);
                var start = post("/iam.auth/external/" + fixture.provider() + "/login", Map.of("tenantId", second.getId()), null).getBody();
                var result = post("/iam.auth/external/" + fixture.provider() + "/complete", Map.of("state", start.path("state").asText(),
                        "browserProof", start.path("browserProof").asText(), "parameters", Map.of("code", "tenant-isolated")), null).getBody();
                assertThat(result.path("failure").asText()).isEqualTo("admissionRejected");
            }
        }
        assertThat(count("iam_external_identity", "provider_id", fixture.provider())).isZero();
    }

    private int count(String table, String column, String value) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where " + column + "=?", Integer.class, value);
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

    private JsonNode completeFlow(String providerId, ResponseEntity<JsonNode> start, String externalUserId) throws Exception {
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = start.getBody();
        var completion = complete(providerId, body, authorize(body, externalUserId), body.path("browserProof").asText());
        assertThat(completion.getStatusCode()).as("%s", completion.getBody()).isEqualTo(HttpStatus.OK);
        return completion.getBody();
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
        return complete(start.path("providerId").asText(PROVIDER), start, code, browserProof);
    }

    private ResponseEntity<JsonNode> complete(String providerId, JsonNode start, String code, String browserProof) {
        String state = start.path("state").asText();
        return post("/iam.auth/external/" + providerId + "/complete", Map.of(
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

    static final class StatusProtocol implements ExternalLoginProtocol, ExternalDirectoryProtocol {
        static final String TYPE = "status_idp";
        volatile String status = "active";
        volatile boolean fail;
        volatile boolean lookupSupported = true;
        volatile boolean omitEnterprise;
        volatile boolean singleEnterprise;
        volatile int calls;
        volatile Runnable lookupHook = () -> {};
        volatile List<String> directoryPeople = List.of();
        volatile boolean failAfterPeoplePage;
        @Override public void validateConfiguration(Map<String, String> configuration) { }
        @Override public Set<String> identityScopeConfigurationKeys() { return Set.of(); }
        @Override public String providerType() { return TYPE; }
        @Override public URI authorizationUri(IdentityProviderDescriptor provider, ExternalAuthorizationContext context) {
            return URI.create("https://idp.test/status?state=" + encode(context.state()));
        }
        @Override public VerifiedExternalIdentity verify(IdentityProviderDescriptor provider, Map<String, String> parameters,
                                                        ExternalAuthorizationContext context) {
            return new VerifiedExternalIdentity(omitEnterprise ? null : "status-corp", parameters.get("code"), null, Instant.now());
        }
        @Override public boolean supportsPersonLookup(IdentityProviderDescriptor provider) { return lookupSupported; }
        @Override public boolean singleEnterpriseApplication(IdentityProviderDescriptor provider) { return singleEnterprise; }
        @Override public Person person(IdentityProviderDescriptor provider, String id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            if (fail) throw new IllegalStateException("status unavailable");
            String observedStatus = status;
            lookupHook.run();
            return new Person(id, id, null, "root", List.of("root"), observedStatus, null, null);
        }
        @Override public Department department(IdentityProviderDescriptor provider, String id) { return new Department(id, null, id); }
        @Override public Page<Department> departments(IdentityProviderDescriptor provider, String root, String cursor, int limit) {
            return new Page<>(List.of(new Department("root", null, "Root")), null);
        }
        @Override public Page<Person> departmentPeople(IdentityProviderDescriptor provider, String department, String cursor, int limit) {
            if ("after-people".equals(cursor)) {
                if (failAfterPeoplePage) throw new net.ximatai.muyun.spring.platform.job.JobFailure("TEST_AFTER_PEOPLE", false);
                return new Page<>(List.of(), null);
            }
            return new Page<>(directoryPeople.stream().map(id -> new Person(id, id, null, "root", List.of("root"), status, null, null)).toList(), failAfterPeoplePage ? "after-people" : null);
        }
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
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var current = entered;
            if (current != null) { current.countDown(); await(release); }
            return new VerifiedExternalIdentity("test-enterprise", parameters.get("code"), null, Instant.now());
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
                    Instant.now());
        }
    }
}
