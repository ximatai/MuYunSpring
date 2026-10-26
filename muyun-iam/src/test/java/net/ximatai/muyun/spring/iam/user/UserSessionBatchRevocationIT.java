package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.*;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(classes = UserSessionBatchRevocationIT.Application.class)
class UserSessionBatchRevocationIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    @Autowired IDatabaseOperations<?> database;
    @Autowired UserSessionDao dao;
    @Autowired UserAccountDao accountDao;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    private UserSessionRecordService records;
    private UserSessionRevocationService revocations;
    private UserAccountService accounts;
    private UserSessionService authentication;
    private final List<UserSessionLifecycleEvent> events = new CopyOnWriteArrayList<>();
    private TransactionTemplate tx;
    private JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        jdbc.update("delete from iam_user_session");
        jdbc.update("delete from iam_user");
        tx = new TransactionTemplate(transactions);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            @Override public <T> T execute(Supplier<T> work) { return tx.execute(status -> work.get()); }
        });
        records = new UserSessionRecordService(dao, database);
        revocations = new UserSessionRevocationService(records, (UserSessionLifecycleEventPublisher) events::add,
                Clock.fixed(NOW, ZoneOffset.UTC));
        accounts = new UserAccountService(accountDao, tenant -> {}, new PasswordHashingService(),
                mock(AccountRoleGrantDao.class), new UserAccountSecurityServices(Optional.empty(),
                UserSecurityEventPublisher.NOOP, revocations, mock(UserSessionPresenceService.class))) {
            @Override public net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService getDataScopeCriteriaService() {
                return new AllowAllDataScopeCriteriaService();
            }
        };
        authentication = new UserSessionService(accounts, records, tenant -> {},
                UserSessionCollaborators.empty(), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @AfterEach void cleanup() {
        PlatformAbilityRuntime.resetMutationTransactionOperator();
        TenantContext.clear(); CurrentUserContext.clear();
    }

    @Test void revokesMultipleDevicesInOneStatementPreservingHistoryAndAuthenticationVisibility() throws Exception {
        var observed = spy(database);
        records = new UserSessionRecordService(dao, observed);
        revocations = new UserSessionRevocationService(records, (UserSessionLifecycleEventPublisher) events::add,
                Clock.fixed(NOW, ZoneOffset.UTC));
        try (var scope = TenantContext.use("tenant-a")) {
            account();
            seed("web"); seed("mobile"); seed("old");
            var old = dao.findById("old");
            assertThat(revocations.logout(old, NOW.minusSeconds(60))).isTrue();
            events.clear();
            // Warm the actual authentication read path before the security command.
            assertThat(authentication.currentUserSnapshot("web")).isPresent();
            assertThat(authentication.currentUserSnapshot("mobile")).isPresent();
            clearInvocations(observed);
            assertThat(revocations.revokeUserSessions("user", "password changed")).isEqualTo(2);
            verify(observed, times(1)).query(startsWith("update "), anyMap());
            assertThat(events).containsExactlyInAnyOrder(UserSessionLifecycleEvent.revoked("user", "web"),
                    UserSessionLifecycleEvent.revoked("user", "mobile"));
            assertThat(authentication.currentUserSnapshot("web")).isEmpty();
            assertThat(authentication.currentUser("mobile")).isEmpty();
            assertThat(dao.findById("old").getRevokedReason()).isEqualTo("logout");
            assertThat(dao.findById("old").getRevokedAt()).isEqualTo(NOW.minusSeconds(60));
            assertThat(dao.findById("web").getVersion()).isOne();
            assertThat(dao.findById("web").getRevokedAt()).isEqualTo(NOW);
            assertThat(dao.findById("web").getRevokedReason()).isEqualTo("password changed");
            assertThat(revocations.revokeUserSessions("user", "second reason")).isZero();
            assertThat(events).hasSize(2);
        }
    }

    @Test void keepsTenantAndPlatformAccountPartitionsEvenInBypassAndSystemContexts() throws Exception {
        seed("platform");
        try (var scope = TenantContext.use("tenant-a")) { seed("a"); }
        try (var scope = TenantContext.use("tenant-b")) { seed("b"); }
        try (var scope = TenantContext.use("tenant-a"); var bypass = TenantContext.bypassTenantFilter("test")) {
            assertThat(revocations.revokeUserSessions("user", "tenant disabled")).isOne();
        }
        assertThat(dao.findById("a").getRevokedAt()).isEqualTo(NOW);
        assertThat(dao.findById("b").getRevokedAt()).isNull();
        try (var system = TenantContext.system("platform account logout")) {
            assertThat(revocations.revokeUserSessions("user", "platform logout")).isOne();
        }
        assertThat(dao.findById("platform").getRevokedAt()).isEqualTo(NOW);
        assertThat(dao.findById("b").getRevokedAt()).isNull();
        assertThat(events).containsExactly(UserSessionLifecycleEvent.revoked("user", "a"),
                UserSessionLifecycleEvent.revoked("user", "platform"));
    }

    @Test void revocationWaitsForHeartbeatAndRejectsSubsequentStaleRenewal() throws Exception {
        seed("heartbeat");
        UserSession stale = dao.findById("heartbeat");
        try (var connection = source.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try {
                connection.createStatement().executeUpdate("update iam_user_session set version=version+1, "
                        + "last_seen_at=localtimestamp where id='heartbeat'");
                var revoked = executor.submit(() -> revocations.revokeUserSessions("user", "security change"));
                awaitBlockedUpdate("changed_id");
                connection.commit();
                assertThat(revoked.get(10, TimeUnit.SECONDS)).isOne();
            } finally { connection.rollback(); }
        }
        stale.setExpiresAt(NOW.plusSeconds(3600));
        assertThat(records.updateSession(stale, stale.getVersion(), NOW)).isZero();
        assertThat(dao.findById("heartbeat").getVersion()).isEqualTo(2);
        assertThat(dao.findById("heartbeat").getRevokedReason()).isEqualTo("security change");
        assertThat(events).containsExactly(UserSessionLifecycleEvent.revoked("user", "heartbeat"));
    }

    @Test void staleRenewalWaitingForBatchCommitCannotReviveSession() throws Exception {
        seed("stale");
        UserSession stale = dao.findById("stale");
        var updated = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var batch = executor.submit(() -> tx.execute(status -> {
                int count = revocations.revokeUserSessions("user", "disabled");
                updated.countDown(); await(commit); return count;
            }));
            await(updated);
            var renewal = executor.submit(() -> records.updateSession(stale, stale.getVersion(), NOW));
            awaitBlockedUpdate("iam_user_session");
            commit.countDown();
            assertThat(batch.get(10, TimeUnit.SECONDS)).isOne();
            assertThat(renewal.get(10, TimeUnit.SECONDS)).isZero();
        } finally { commit.countDown(); }
        assertThat(dao.findById("stale").getRevokedAt()).isEqualTo(NOW);
    }

    @Test void singleAndBatchRevocationPublishExactlyOneEventPerTransition() throws Exception {
        seed("shared"); seed("other");
        UserSession stale = dao.findById("shared");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var single = executor.submit(() -> { await(start); return revocations.logout(stale, NOW.minusSeconds(1)); });
            var batch = executor.submit(() -> { await(start); return revocations.revokeUserSessions("user", "disabled"); });
            start.countDown();
            int total = batch.get(10, TimeUnit.SECONDS) + (single.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(total).isEqualTo(2);
        }
        assertThat(events).hasSize(2).extracting(UserSessionLifecycleEvent::sessionId)
                .containsExactlyInAnyOrder("shared", "other");
        assertThat(dao.findById("shared").getRevokedReason()).isIn("logout", "disabled");
    }

    @Test void disablingAccountWaitsForAdmittedLoginAndRevokesItsNewSession() throws Exception {
        UserAccount verified = account();
        var inserted = new CountDownLatch(1);
        var commitLogin = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var login = executor.submit(() -> accounts.admitLogin(verified, true, NOW, null, null, admitted -> {
                try { seed("racing-login"); } catch (Exception e) { throw new AssertionError(e); }
                inserted.countDown(); await(commitLogin); return admitted;
            }));
            await(inserted);
            var disable = executor.submit(() -> tx.execute(status -> {
                var disabled = VersionedRecordMutation.update(accountDao,
                        () -> accountDao.findById("user"), draft -> draft.setEnabled(false));
                accounts.afterUpdate(disabled, 1);
                return 1;
            }));
            awaitBlockedUpdate("iam_user");
            commitLogin.countDown();
            assertThat(login.get(10, TimeUnit.SECONDS)).isPresent();
            assertThat(disable.get(10, TimeUnit.SECONDS)).isOne();
        } finally { commitLogin.countDown(); }
        assertThat(dao.findById("racing-login").getRevokedReason()).isEqualTo("user disabled");
        assertThat(accounts.admitLogin(verified, true, NOW, null, null, user -> "must not issue")).isEmpty();
        assertThat(events).containsExactly(UserSessionLifecycleEvent.revoked("user", "racing-login"));
    }

    @Test void batchPrimitiveAcceptsRuntimeTableMetadataAndPreservesUpdateAudit() throws Exception {
        seed("runtime");
        var staticTable = PlatformEntityManagers.entityMetaResolver().resolve(UserSession.class).asTableMeta();
        var runtimeFields = staticTable.getFields().stream().map(field ->
                new RuntimeFieldMeta(field.getFieldName(), field.getColumnName(), field.getColumnType(),
                        field.getElementColumnType(), field.getFieldType(), null, field.isId())).toList();
        var runtimeTable = TableMeta.of(staticTable.getSchema(), staticTable.getTableName(), runtimeFields,
                runtimeFields.stream().filter(FieldMeta::isId).findFirst().orElseThrow());
        var conditions = new LinkedHashMap<String, Object>();
        conditions.put("userId", "user"); conditions.put("revokedAt", null);
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("operator", "operator"))) {
            assertThat(ConditionalBatchUpdate.updateReturningIds(database, runtimeTable,
                    Map.of("revokedAt", NOW, "revokedReason", "runtime command"), conditions, NOW))
                    .containsExactly("runtime");
        }
        var stored = dao.findById("runtime");
        assertThat(stored.getUpdatedBy()).isEqualTo("operator");
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
        assertThat(stored.getIssuedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThatThrownBy(() -> ConditionalBatchUpdate.updateReturningIds(database, runtimeTable,
                Map.of("revokedReason", "bad"), Map.of(), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionalBatchUpdate.updateReturningIds(database, runtimeTable,
                Map.of("tenantId", "other"), conditions, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionalBatchUpdate.updateReturningIds(database, runtimeTable,
                Map.of("revoked_reason = null --", "bad"), conditions, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(dao.findById("runtime").getVersion()).isOne();
    }

    private UserAccount account() {
        var user = new UserAccount(); user.setId("user"); user.setUsername("user"); user.setTitle("User");
        user.setPasswordHash("verified-hash"); user.setEnabled(true);
        EntityLifecycle.prepareInsert(user, NOW.minusSeconds(60)); accountDao.insert(user); return user;
    }
    private void seed(String id) throws Exception {
        var session = new UserSession(); session.setId(id); session.setUserId("user"); session.setUsername("user");
        session.setTokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(id.getBytes(StandardCharsets.UTF_8))));
        session.setIssuedAt(NOW.minusSeconds(60)); session.setExpiresAt(NOW.plusSeconds(600));
        session.setMaxExpiresAt(NOW.plusSeconds(3600)); session.setLastSeenAt(NOW.minusSeconds(60));
        records.issue(session);
    }
    private void awaitBlockedUpdate(String queryFragment) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' "
                    + "and lower(query) like 'update %' and position(? in query) > 0",
                    Integer.class, queryFragment) > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("update did not wait for the expected row lock: " + queryFragment);
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = UserSessionDao.class)
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
