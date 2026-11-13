package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.EntityMetaResolver;
import net.ximatai.muyun.database.core.orm.SimpleEntityManager;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.schema.PlatformEntityManagers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL contract for the ownership backstops: the deterministic primary key and the unique
 * {@code ownership_scope_key} index must prevent two rows for the same scope, including the null-tenant
 * platform default and concurrent creation.
 */
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = PasswordSecurityPolicyRepositoryIT.Application.class)
class PasswordSecurityPolicyRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired
    PasswordSecurityPolicyDao dao;

    @Test
    void initializesTheDatabaseDefaultOnceAndResolvesEditsWithoutRestart() {
        var service = new PasswordSecurityPolicyService(dao,
                org.mockito.Mockito.mock(net.ximatai.muyun.spring.iam.tenant.TenantService.class), org.mockito.Mockito.mock(PasswordPolicyRuleService.class));
        var initializer = new net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor(
                java.util.List.of(service), java.util.List.of());
        initializer.initializeAll();
        var defaults = dao.findById(PasswordSecurityPolicyService.PLATFORM_POLICY_ID);
        assertThat(defaults.getMaxFailedAttempts()).isEqualTo(5);
        assertThat(defaults.getLockDurationSeconds()).isEqualTo(900);
        try (var scope = net.ximatai.muyun.spring.common.tenant.TenantContext.system("edit platform policy")) {
            defaults.setMaxFailedAttempts(2);
            defaults.setLockDurationSeconds(60);
            service.update(defaults);
            assertThatThrownBy(() -> service.delete(defaults.getId()))
                    .hasMessageContaining("cannot be deleted");
        }
        initializer.initializeAll();
        var resolver = new PasswordSecurityPolicyResolver(service, org.mockito.Mockito.mock(PasswordPolicyRuleService.class));
        assertThat(resolver.resolve(null).maxFailedAttempts()).isEqualTo(2);
        assertThat(resolver.resolve("any-inheriting-tenant").lockDuration()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(dao.findById(PasswordSecurityPolicyService.PLATFORM_POLICY_ID).getVersion()).isEqualTo(1);
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired DataSource source;

    @org.junit.jupiter.api.BeforeEach
    void installMutationTransactions() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.configureMutationTransactionOperator(
                new net.ximatai.muyun.spring.ability.MutationTransactionOperator() {
                    public <T> T execute(java.util.function.Supplier<T> work) {
                        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> work.get());
                    }
                    public void lock(String scope, String key) {
                        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key);
                    }
                });
    }

    @AfterEach
    void clear() {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.resetMutationTransactionOperator();
        unavailableDao.query(net.ximatai.muyun.database.core.orm.Criteria.of(),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE))
                .forEach(state -> unavailableDao.deleteById(state.getId()));
        ruleDao.query(net.ximatai.muyun.database.core.orm.Criteria.of(),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE))
                .forEach(rule -> ruleDao.deleteById(rule.getId()));
        dao.query(net.ximatai.muyun.database.core.orm.Criteria.of(),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE))
                .forEach(policy -> dao.deleteById(policy.getId()));
    }

    @Autowired UnavailablePasswordLoginService unavailableLogins;
    @Autowired UnavailablePasswordLoginDao unavailableDao;
    @Autowired PasswordSecurityWorkspaceService workspace;
    @Autowired PasswordPolicyRuleDao ruleDao;

    @Test
    void rejectedUnknownLoginsCommitFailureStateWithoutCreatingAnAccountOrSession() {
        Instant at = Instant.parse("2026-11-12T00:00:00Z");
        var accounts = org.mockito.Mockito.mock(UserAccountService.class);
        var sessionDao = org.mockito.Mockito.mock(UserSessionDao.class);
        var sessions = new UserSessionService(accounts,
                new UserSessionRecordService(sessionDao, org.mockito.Mockito.mock(IDatabaseOperations.class)),
                tenant -> {}, UserSessionCollaborators.empty(), java.time.Clock.fixed(at, java.time.ZoneOffset.UTC), unavailableLogins);
        for (int attempt = 1; attempt <= 5; attempt++) {
            int currentAttempt = attempt;
            assertThatThrownBy(() -> sessions.login("tenant-a", "missing", "wrong"))
                    .isInstanceOfSatisfying(net.ximatai.muyun.spring.common.exception.AuthenticationFailedException.class, error -> {
                        assertThat(error.httpStatus()).isEqualTo(401);
                        assertThat(error.code()).isEqualTo("LOGIN_BAD_CREDENTIALS");
                        if (currentAttempt == 5) {
                            var feedback = (LoginFailureFeedback) error.details().get("loginFailure");
                            assertThat(feedback.lockTriggered()).isTrue();
                            assertThat(feedback.lockedUntil()).isEqualTo(at.plusSeconds(900));
                        }
                    });
        }
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.times(5)).spendPasswordVerification("wrong");
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(sessionDao, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any());
        assertThat(unavailableDao.findById(UnavailablePasswordLoginService.key("tenant-a", "missing")).getLockedUntil())
                .isEqualTo(at.plusSeconds(900));
    }

    @Test
    void unavailableNamesHaveTheSameThresholdLockExpiryAndTenantIsolation() {
        Instant at = Instant.parse("2026-11-12T00:00:00Z");
        try (var ignored = net.ximatai.muyun.spring.common.tenant.TenantContext.system("unknown-login-it")) {
            assertThat(unavailableLogins.recordFailure("tenant-a", "missing", at)).isNull();
            assertThat(unavailableLogins.recordFailure("tenant-a", "missing", at)).isNull();
            assertThat(unavailableLogins.recordFailure("tenant-a", "missing", at).remainingAttempts()).isEqualTo(2);
            assertThat(unavailableLogins.recordFailure("tenant-a", "missing", at).remainingAttempts()).isEqualTo(1);
            var locked = unavailableLogins.recordFailure("tenant-a", "missing", at);
            assertThat(locked.lockTriggered()).isTrue();
            assertThat(locked.lockedUntil()).isEqualTo(at.plusSeconds(900));
            var repeated = unavailableLogins.recordFailure("tenant-a", "missing", at.plusSeconds(1));
            assertThat(repeated.lockTriggered()).isFalse();
            assertThat(repeated.lockedUntil()).isEqualTo(locked.lockedUntil());
            assertThat(unavailableLogins.recordFailure("tenant-b", "missing", at)).isNull();
            assertThat(unavailableLogins.recordFailure(null, "missing", at)).isNull();
            assertThat(unavailableLogins.recordFailure("tenant-a", "missing", at.plusSeconds(900))).isNull();
            assertThat(unavailableLogins.currentLock("tenant-a", "missing", at.plusSeconds(900))).isNull();
            assertThat(unavailableLogins.purgeExpired(at.plusSeconds(900 + 86401), 200)).isEqualTo(3);
        }
    }

    @Test
    void unavailableNamesFollowCustomAndDisabledPoliciesWithoutClearingActiveLocks() {
        Instant at = Instant.parse("2026-11-12T00:00:00Z");
        var custom = new UnavailablePasswordLoginService(unavailableDao,
                net.ximatai.muyun.spring.iam.support.UserAccountServiceTestFactory.defaultResolver(
                        LoginLockoutPolicy.of(2, java.time.Duration.ofSeconds(60))));
        var disabled = new UnavailablePasswordLoginService(unavailableDao,
                net.ximatai.muyun.spring.iam.support.UserAccountServiceTestFactory.defaultResolver(LoginLockoutPolicy.of(0, null)));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        try (var ignored = net.ximatai.muyun.spring.common.tenant.TenantContext.system("custom-unavailable-login-it")) {
            assertThat(transaction.execute(status -> custom.recordFailure("tenant-custom", "missing", at)).remainingAttempts()).isEqualTo(1);
            assertThat(transaction.execute(status -> custom.recordFailure("tenant-custom", "missing", at)).lockedUntil()).isEqualTo(at.plusSeconds(60));
            assertThat(transaction.execute(status -> disabled.recordFailure("tenant-custom", "missing", at.plusSeconds(1))).lockedUntil()).isEqualTo(at.plusSeconds(60));
            assertThat(transaction.<LoginFailureFeedback>execute(status -> disabled.recordFailure("tenant-disabled", "missing", at))).isNull();
            assertThat(unavailableDao.findById(UnavailablePasswordLoginService.key("tenant-disabled", "missing"))).isNull();
        }
        try (var ignored = net.ximatai.muyun.spring.common.tenant.TenantContext.use("tenant-other")) {
            assertThatThrownBy(() -> unavailableLogins.currentLock("tenant-custom", "missing", at))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        }
    }

    @Test
    void concurrentUnavailableAttemptsDoNotLoseFailuresOrExtendAnExistingLock() throws Exception {
        Instant at = Instant.parse("2026-11-12T00:00:00Z");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(index -> pool.submit(() -> {
                try (var ignored = net.ximatai.muyun.spring.common.tenant.TenantContext.system("concurrent-login-it")) {
                    return unavailableLogins.recordFailure("tenant-a", "missing", at);
                }
            })).toList();
            int triggered = 0;
            for (var task : tasks) {
                var feedback = task.get(15, TimeUnit.SECONDS);
                if (feedback != null && feedback.lockTriggered()) triggered++;
            }
            assertThat(triggered).isEqualTo(1);
            assertThat(unavailableDao.findById(UnavailablePasswordLoginService.key("tenant-a", "missing")).getLockedUntil())
                    .isEqualTo(at.plusSeconds(900));
        }
    }

    @Test
    void workspaceRollsBackRulesWhenPolicyValidationFails() {
        var policies = new PasswordSecurityPolicyService(dao,
                org.mockito.Mockito.mock(net.ximatai.muyun.spring.iam.tenant.TenantService.class),
                new PasswordPolicyRuleService(ruleDao));
        new net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor(
                java.util.List.of(policies), java.util.List.of()).initializeAll();
        try (var ignored = net.ximatai.muyun.spring.common.tenant.TenantContext.system("atomic-workspace-it")) {
            var policy = policies.findManagedByScope(null).orElseThrow();
            policy.setMaxFailedAttempts(-1);
            var rule = new PasswordPolicyRule();
            rule.setTitle("new rule"); rule.setPattern(".{8,}"); rule.setMessage("eight characters");
            rule.setEnabled(true); rule.setSortOrder(1);
            var access = new PasswordSecurityWorkspaceService.Access() {
                public void authorize(String module, net.ximatai.muyun.spring.common.platform.PlatformAction action, String id) { }
                public void validate(net.ximatai.muyun.spring.common.model.contract.EntityContract supplied,
                                     net.ximatai.muyun.spring.common.model.contract.EntityContract existing) { }
            };
            assertThatThrownBy(() -> workspace.save(new PasswordSecurityWorkspaceService.Command(
                    null, policy, java.util.List.of(), java.util.List.of(rule)), access))
                    .hasMessageContaining("must not be negative");
            assertThat(new PasswordPolicyRuleService(ruleDao).managedRulesForScope(null)).isEmpty();
            assertThat(policies.findManagedByScope(null).orElseThrow().getMaxFailedAttempts()).isEqualTo(5);
        }
    }

    @Test
    void platformScopeAllowsOnlyOneRowEvenWithNullTenant() {
        dao.insert(policy(null, PasswordSecurityPolicyService.PLATFORM_POLICY_ID));

        assertThatThrownBy(() -> dao.insert(policy(null, PasswordSecurityPolicyService.PLATFORM_POLICY_ID)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void everyTenantScopeAllowsOnlyOneRow() {
        dao.insert(policy("tenant-a", PasswordSecurityPolicyService.tenantPolicyId("tenant-a")));
        dao.insert(policy("tenant-b", PasswordSecurityPolicyService.tenantPolicyId("tenant-b")));

        assertThatThrownBy(() -> dao.insert(
                policy("tenant-a", PasswordSecurityPolicyService.tenantPolicyId("tenant-a"))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void compositeOwnershipScopeKeyIsAlsoUnique() {
        // Different primary keys but the same tenant + ownership scope must still be rejected by the
        // composite unique index, proving the index is a real database backstop (not just a column).
        dao.insert(policy("tenant-a", "iam.psp.aaaaaaaaaaaaaaaaaaaaaaaa"));
        assertThatThrownBy(() -> dao.insert(policy("tenant-a", "iam.psp.bbbbbbbbbbbbbbbbbbbbbbbb")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void concurrentCreationForOneScopeCommitsExactlyOneRow() throws Exception {
        String id = PasswordSecurityPolicyService.tenantPolicyId("tenant-concurrent");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> insertRacing(id, start, success, failure));
            Future<?> second = pool.submit(() -> insertRacing(id, start, success, failure));
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(failure.get()).isEqualTo(1);
    }

    private void insertRacing(String id, CountDownLatch start, AtomicInteger success, AtomicInteger failure) {
        try {
            start.await(10, TimeUnit.SECONDS);
            dao.insert(policy("tenant-concurrent", id));
            success.incrementAndGet();
        } catch (Exception expected) {
            failure.incrementAndGet();
        }
    }

    private PasswordSecurityPolicy policy(String tenantId, String id) {
        PasswordSecurityPolicy policy = new PasswordSecurityPolicy();
        policy.setId(id);
        policy.setTenantId(tenantId);
        policy.setOwnershipScopeKey(tenantId == null
                ? PasswordSecurityPolicyService.PLATFORM_SCOPE_KEY
                : PasswordSecurityPolicyService.TENANT_SCOPE_PREFIX + tenantId);
        policy.setTitle("policy");
        policy.setComplexityMode(PasswordSecurityPolicyMode.CUSTOM);
        policy.setLockoutMode(PasswordSecurityPolicyMode.INHERIT);
        EntityLifecycle.prepareInsert(policy, Instant.now());
        return policy;
    }

    @Test
    void managementReadRespectsTheCallerTenantScope() {
        dao.insert(policy(null, PasswordSecurityPolicyService.PLATFORM_POLICY_ID));
        dao.insert(policy("tenant-a", PasswordSecurityPolicyService.tenantPolicyId("tenant-a")));
        dao.insert(policy("tenant-b", PasswordSecurityPolicyService.tenantPolicyId("tenant-b")));
        PasswordSecurityPolicyService service = new PasswordSecurityPolicyService(
                dao, org.mockito.Mockito.mock(net.ximatai.muyun.spring.iam.tenant.TenantService.class), org.mockito.Mockito.mock(PasswordPolicyRuleService.class));

        try (net.ximatai.muyun.spring.common.tenant.TenantContext.Scope ignored =
                     net.ximatai.muyun.spring.common.tenant.TenantContext.use("tenant-a")) {
            assertThat(service.findManagedByScope("tenant-a")).isPresent();
            assertThat(service.findManagedByScope("tenant-b")).isEmpty();
            assertThat(service.findManagedByScope(null)).isEmpty();
        }
        try (net.ximatai.muyun.spring.common.tenant.TenantContext.Scope ignored =
                     net.ximatai.muyun.spring.common.tenant.TenantContext.system("tenant-isolation-it")) {
            assertThat(service.findManagedByScope(null)).isPresent();
            assertThat(service.findManagedByScope("tenant-b")).isPresent();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = PasswordSecurityPolicyDao.class)
    static class Application {
        @Bean
        UnavailablePasswordLoginService unavailableLogins(UnavailablePasswordLoginDao dao) {
            return new UnavailablePasswordLoginService(dao,
                    net.ximatai.muyun.spring.iam.support.UserAccountServiceTestFactory.defaultResolver());
        }

        @Bean
        PasswordSecurityWorkspaceService workspace(PasswordSecurityPolicyDao dao, PasswordPolicyRuleDao ruleDao) {
            var rules = new PasswordPolicyRuleService(ruleDao);
            var policies = new PasswordSecurityPolicyService(dao,
                    org.mockito.Mockito.mock(net.ximatai.muyun.spring.iam.tenant.TenantService.class), rules);
            return new PasswordSecurityWorkspaceService(policies, rules);
        }

        @Bean
        DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName()).build();
        }

        @Bean
        EntityMetaResolver entityMetaResolver() {
            return PlatformEntityManagers.entityMetaResolver();
        }

        @Bean
        SimpleEntityManager simpleEntityManager(IDatabaseOperations<?> database, EntityMetaResolver resolver) {
            return PlatformEntityManagers.simpleEntityManager(database, resolver);
        }
    }
}
