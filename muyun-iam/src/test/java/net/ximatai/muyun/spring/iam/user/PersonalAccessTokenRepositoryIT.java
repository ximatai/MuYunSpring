package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.EntityMetaResolver;
import net.ximatai.muyun.database.core.orm.SimpleEntityManager;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.schema.PlatformEntityManagers;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = PersonalAccessTokenRepositoryIT.Application.class)
class PersonalAccessTokenRepositoryIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired PersonalAccessTokenDao dao;
    @Autowired UserAccountDao accountDao;
    @Autowired IDatabaseOperations<?> database;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource source;
    private PersonalAccessTokenStore store;
    private UserAccountService accounts;

    @BeforeEach void setup() {
        var tx = new TransactionTemplate(transactions);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return tx.execute(status -> work.get()); }
        });
        store = new PersonalAccessTokenStore(dao, database);
        accounts = new UserAccountService(accountDao, tenant -> {}, new PasswordHashingService(),
                mock(AccountRoleGrantDao.class), new UserAccountSecurityServices(Optional.empty(),
                UserSecurityEventPublisher.NOOP, mock(UserSessionRevocationService.class),
                mock(UserSessionPresenceService.class), store)) {
            @Override public net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService getDataScopeCriteriaService() {
                return new AllowAllDataScopeCriteriaService();
            }
        };
    }
    @AfterEach void cleanup() { PlatformAbilityRuntime.resetMutationTransactionOperator(); }

    @Test void staleUsageCannotOverwriteRevocationAndTargetTenantCannotBindCredential() {
        var token = token("owner");
        try (var tenant = TenantContext.use("business-a")) {
            store.issue(token);
            assertThat(TenantContext.currentTenantId()).contains("business-a");
        }
        var stale = store.findByHash(token.getTokenHash());
        assertThat(stale.getTenantId()).isNull();
        try (var tenant = TenantContext.use("business-b")) {
            assertThat(store.ownedBy("owner")).extracting(PersonalAccessToken::getId).contains(token.getId());
            assertThat(store.revoke("other", token.getId(), Instant.now())).isFalse();
            assertThat(store.revoke("owner", token.getId(), Instant.now())).isTrue();
            store.touch(stale, Instant.now());
            assertThat(TenantContext.currentTenantId()).contains("business-b");
        }
        var stored = dao.findById(token.getId());
        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getLastUsedAt()).isNull();
        assertThat(store.revoke("owner", token.getId(), Instant.now())).isFalse();
    }

    @Test void onlyRevokedOwnerCredentialsCanBeSoftDeletedAndStaleUsageCannotRestoreThem() {
        var token = token("delete-owner");
        token.setExpiresAt(Instant.now().minusSeconds(1));
        store.issue(token);
        assertThat(store.deleteRevoked("other", token.getId(), Instant.now())).isFalse();
        assertThatThrownBy(() -> store.deleteRevoked("delete-owner", token.getId(), Instant.now()))
                .isInstanceOfSatisfying(net.ximatai.muyun.spring.common.exception.PlatformException.class,
                        error -> { assertThat(error.code()).isEqualTo("VALIDATION_FAILED"); assertThat(error.httpStatus()).isEqualTo(400); });
        assertThat(dao.findById(token.getId()).getDeleted()).isFalse();
        assertThat(store.revoke("delete-owner", token.getId(), Instant.now())).isTrue();
        var stale = store.findByHash(token.getTokenHash());
        Instant deletedAt = Instant.now();
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("delete-owner", "owner"));
             var tenant = TenantContext.use("unrelated-target")) {
            assertThat(store.deleteRevoked("other", token.getId(), deletedAt)).isFalse();
            assertThat(store.deleteRevoked("delete-owner", token.getId(), deletedAt)).isTrue();
            assertThat(TenantContext.currentTenantId()).contains("unrelated-target");
        }
        store.touch(stale, Instant.now());
        var stored = dao.findById(token.getId());
        assertThat(stored.getDeleted()).isTrue();
        assertThat(stored.getDeletedAt()).isCloseTo(deletedAt, within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(stored.getDeletedBy()).isEqualTo("delete-owner");
        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getLastUsedAt()).isNull();
        assertThat(store.ownedBy("delete-owner")).extracting(PersonalAccessToken::getId).doesNotContain(token.getId());
        assertThat(store.findByHash(token.getTokenHash())).isNull();
        assertThat(store.deleteRevoked("delete-owner", token.getId(), Instant.now())).isFalse();
        assertThat(store.deleteRevoked("delete-owner", "missing", Instant.now())).isFalse();
    }

    @Test void accountDisableWaitsForIssuanceAndPermanentlyRevokesItsNewCredential() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        var owner = new UserAccount(); owner.setId(id); owner.setUsername(id); owner.setTitle(id); owner.setEnabled(true);
        EntityLifecycle.prepareInsert(owner, Instant.now()); accountDao.insert(owner);
        var issued = new CountDownLatch(1); var release = new CountDownLatch(1);
        var gated = new PersonalAccessTokenStore(dao, database) {
            @Override public void issue(PersonalAccessToken token) {
                super.issue(token); issued.countDown(); await(release);
            }
        };
        var sessions = mock(UserSessionService.class);
        var user = CurrentUser.systemUser(id, id);
        when(sessions.currentUserSnapshot("session")).thenReturn(Optional.of(user));
        var service = new PersonalAccessTokenService(gated, accounts, sessions, UserSessionCollaborators.empty(),
                Clock.systemUTC(), net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher.NOOP, new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(), tenantId -> {});
        try (var executor = Executors.newFixedThreadPool(2)) {
            var creation = executor.submit(() -> {
                try (var actor = CurrentUserContext.use(user)) { return service.create("session", "concurrent tool", 90); }
            });
            try {
                assertThat(issued.await(10, TimeUnit.SECONDS)).isTrue();
                var disable = executor.submit(() -> {
                    try (var tenant = TenantContext.system("disable token owner");
                         var actor = CurrentUserContext.use(CurrentUser.systemUser("operator", "operator"))) {
                        return new TransactionTemplate(transactions).execute(status -> {
                            var disabled = net.ximatai.muyun.spring.ability.VersionedRecordMutation.update(
                                    accountDao, () -> accountDao.findById(id), draft -> draft.setEnabled(false));
                            accounts.afterUpdate(disabled, 1);
                            return 1;
                        });
                    }
                });
                awaitAccountLock();
                release.countDown();
                var result = creation.get(20, TimeUnit.SECONDS);
                assertThat(disable.get(20, TimeUnit.SECONDS)).isOne();
                assertThat(dao.findById(result.accessToken().id()).getRevokedAt()).isNotNull();
                try (var tenant = TenantContext.system("enable token owner")) { accounts.enable(id); }
                assertThat(dao.findById(result.accessToken().id()).getRevokedAt()).isNotNull();
                assertThat(accountDao.findById(id).getLastLoginAt()).isNull();
            } finally { release.countDown(); }
        }
    }

    private void awaitAccountLock() throws Exception {
        var jdbc = new JdbcTemplate(source); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and lower(query) like 'update %' and position('iam_user' in query)>0", Integer.class) > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("account disable did not wait for issuance row lock");
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private PersonalAccessToken token(String userId) {
        var token = new PersonalAccessToken(); token.setUserId(userId); token.setName("tool");
        token.setTokenHash(UUID.randomUUID().toString()); token.setDisplayPrefix("muyun_pat_test");
        token.setExpiresAt(Instant.now().plusSeconds(3600)); return token;
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = PersonalAccessTokenDao.class)
    static class Application {
        @Bean DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create().url(postgres.getJdbcUrl())
                    .username(postgres.getUsername()).password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean EntityMetaResolver entityMetaResolver() { return PlatformEntityManagers.entityMetaResolver(); }
        @Bean SimpleEntityManager simpleEntityManager(IDatabaseOperations<?> db, EntityMetaResolver resolver) {
            return PlatformEntityManagers.simpleEntityManager(db, resolver);
        }
    }
}
