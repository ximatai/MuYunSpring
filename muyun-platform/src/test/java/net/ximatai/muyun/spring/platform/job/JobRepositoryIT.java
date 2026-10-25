package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.notification.*;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = JobRepositoryIT.Application.class)
class JobRepositoryIT extends PlatformPostgresIntegrationTest {
    @Autowired JobPlanDao plans;
    @Autowired JobRunDao runs;
    @Autowired IDatabaseOperations<?> db;
    @Autowired PlatformTransactionManager manager;
    private JobService first, second;
    private TransactionTemplate tx;
    private final AtomicInteger executions = new AtomicInteger();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); executions.set(0);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("access", (JobManagementAccess) tenant -> { });
        beans.registerSingleton("recipients", (JobFailureRecipientResolver) tenant -> BusinessNotificationRecipients.none());
        var registry = new JobRegistry(List.of(handler("test.platform", JobScope.PLATFORM), handler("test.tenant", JobScope.TENANT)));
        first = service(registry, beans); second = service(registry, beans);
        global(() -> { db.execute("delete from platform_job_run"); db.execute("delete from platform_job_plan");
            db.execute("create table if not exists job_fencing_fixture (id varchar primary key, value integer)");
            db.execute("delete from job_fencing_fixture"); return null; });
    }
    private JobService service(JobRegistry registry, DefaultListableBeanFactory beans) {
        return new JobService(plans, runs, db, manager, registry, beans.getBeanProvider(JobFailureRecipientResolver.class),
                beans.getBeanProvider(BusinessNotificationDelivery.class), beans.getBeanProvider(JobManagementAccess.class), Duration.ofSeconds(30), 90);
    }
    private JobHandler handler(String code, JobScope scope) {
        return new JobHandler() {
            public JobDefinition definition() {
                return new JobDefinition(code, "test.name", scope, scope == JobScope.PLATFORM
                        ? JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ofMinutes(1)) : null,
                        "default", null, Duration.ofMinutes(5), 3, Duration.ZERO, input -> "shared", false, 3, true, scope == JobScope.TENANT ? 30 : null);
            }
            public JobResult execute(JobContext context) {
                context.transaction(() -> { executions.incrementAndGet(); return null; });
                return JobResult.success("COMPLETE", Map.of("processed", 1L));
            }
        };
    }
    @Test void concurrentScansGenerateExactlyOneAndAdvanceAtomically() throws Exception {
        first.registerDefaults(); second.registerDefaults();
        global(() -> { assertThat(plans.query(Criteria.of(), PageRequest.of(1, 10))).hasSize(1);
            db.execute("update platform_job_plan set next_fire_at = clock_timestamp() - interval '3 hours'"); return null; });
        together(() -> { first.scan(); return null; }, () -> { second.scan(); return null; });
        global(() -> {
            assertThat(runs.query(Criteria.of(), PageRequest.of(1, 10))).hasSize(1);
            assertThat(plans.query(Criteria.of(), PageRequest.of(1, 1)).getFirst().getNextFireAt()).isNull(); return null;
        });
        assertThat(first.runOne("default", "node-a")).isTrue();
        global(() -> {
            var plan = plans.query(Criteria.of(), PageRequest.of(1, 1)).getFirst();
            var run = runs.query(Criteria.of(), PageRequest.of(1, 1)).getFirst();
            assertThat(plan.getNextFireAt()).isEqualTo(run.getFinishedAt().plusSeconds(3600));
            assertThat(plan.getLastStatus()).isEqualTo("SUCCEEDED"); return null;
        });
    }
    @Test void cronMergesMissedTriggersAndSkipsUnfinishedPreviousRun() {
        var plan = plan("test.platform", null, JobSchedule.cron("0 * * * * *", "UTC"));
        global(() -> { db.execute("update platform_job_plan set next_fire_at = clock_timestamp() - interval '3 hours' where id = ?", plan.getId()); return null; });
        first.scan();
        global(() -> { db.execute("update platform_job_plan set next_fire_at = clock_timestamp() - interval '2 hours' where id = ?", plan.getId()); return null; });
        first.scan();
        global(() -> { assertThat(runs.query(Criteria.of(), PageRequest.of(1, 10))).hasSize(1);
            assertThat(plans.findById(plan.getId()).getSkippedCount()).isEqualTo(1); return null; });
    }
    @Test void concurrentClaimsIssueOneLeasePerEffectiveKeyAndOnlyRegisteredTypes() throws Exception {
        queued("test.platform", null, "v1"); queued("test.platform", null, "v2");
        var claims = together(() -> first.claim("default", "a"), () -> second.claim("default", "b"));
        assertThat(claims.stream().filter(Objects::nonNull)).hasSize(1);
        assertThat(second.claim("missing", "b")).isNull();
        var a = queued("test.tenant", "tenant-a", "v1"); var b = queued("test.tenant", "tenant-b", "v1");
        assertThat(first.claim("default", "a").getId()).isIn(a.getId(), b.getId());
        assertThat(second.claim("default", "b").getId()).isIn(a.getId(), b.getId());
    }
    @Test void blockedMutexBacklogCannotStarveAnotherTenant() {
        queued("test.platform", null, "active"); first.claim("default", "active-owner");
        for (int i = 0; i < 150; i++) queued("test.platform", null, "blocked:" + i);
        var independent = queued("test.tenant", "tenant-a", "independent");
        assertThat(second.claim("default", "other-owner").getId()).isEqualTo(independent.getId());
    }
    @Test void expiredLeaseIsTakenOverOnSameRunAndOldTokenCannotWrite() {
        queued("test.platform", null, "v1"); var old = first.claim("default", "old");
        expire(old.getId());
        var replacement = second.claim("default", "new");
        assertThat(replacement.getId()).isEqualTo(old.getId()); assertThat(replacement.getAttempts()).isEqualTo(2);
        assertThatThrownBy(() -> first.protectedTransaction(old.getId(), old.getLeaseToken(), () -> { }, () -> {
            db.execute("insert into job_fencing_fixture values ('stale', 1)"); return null;
        })).isInstanceOf(JobFailure.class);
        second.protectedTransaction(replacement.getId(), replacement.getLeaseToken(), () -> { }, () -> {
            db.execute("insert into job_fencing_fixture values ('new', 1)"); return null;
        });
        assertThat(db.query("select id from job_fencing_fixture")).extracting(row -> row.get("id")).containsExactly("new");
    }
    @Test void protectedRowMakesRenewalAndTakeoverWaitIncludingDifferentRunWithSameKey() throws Exception {
        queued("test.platform", null, "v1"); var leased = first.claim("default", "old");
        var waiting = queued("test.platform", null, "v2");
        global(() -> { db.execute("update platform_job_run set lease_until=clock_timestamp()+interval '1 second',available_at=clock_timestamp()+interval '1 day' where id=?", leased.getId()); return null; });
        var pool = Executors.newFixedThreadPool(3); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            var write = pool.submit(() -> first.protectedTransaction(leased.getId(), leased.getLeaseToken(), () -> { }, () -> {
                held.countDown(); await(release); db.execute("insert into job_fencing_fixture values ('protected', 1)"); return null;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue(); Thread.sleep(1200);
            var renewal = pool.submit(() -> { first.renewNode("old"); return null; });
            var takeover = pool.submit(() -> second.claim("default", "new"));
            Thread.sleep(200); assertThat(renewal.isDone()).isFalse(); assertThat(takeover.isDone()).isFalse();
            release.countDown(); write.get(5, TimeUnit.SECONDS); renewal.get(5, TimeUnit.SECONDS);
            var next = takeover.get(5, TimeUnit.SECONDS); assertThat(next).isNotNull();
            assertThat(next.getId()).isIn(leased.getId(), waiting.getId());
            assertThat(db.query("select id from job_fencing_fixture")).hasSize(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void enqueueRollsBackWithCallerAndSignalsOnlyAfterCommitAndDeduplicatesWithinTenant() {
        var signals = new AtomicInteger(); first.onEnqueued(channel -> signals.incrementAndGet());
        try (var system = TenantContext.system("test")) {
            tx.execute(status -> { first.enqueue("test.platform", null, Map.of(), "r:v1"); status.setRollbackOnly(); return null; });
        }
        assertThat(signals).hasValue(0);
        global(() -> { assertThat(runs.query(Criteria.of(), PageRequest.of(1, 10))).isEmpty(); return null; });
        var a = queued("test.tenant", "a", "r:v1"); var same = queued("test.tenant", "a", "r:v1");
        var b = queued("test.tenant", "b", "r:v1");
        assertThat(same.getId()).isEqualTo(a.getId()); assertThat(b.getId()).isNotEqualTo(a.getId());
        assertThat(signals).hasValue(2);
        try (var tenant = TenantContext.use("a")) {
            assertThatThrownBy(() -> first.enqueue("test.tenant", "b", Map.of(), "r:v1"))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        }
    }
    @Test void inputAndRevisionRemainFrozenAcrossPlanChangesAndReleaseDoesNotConsumeAttempt() {
        var plan = plan("test.platform", null, JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ZERO)); first.scan();
        var run = first.claim("default", "one");
        assertThat(JobService.parameters(run.getParametersJson())).containsEntry("revision", 1);
        // Scanning updates runtime state only; the configuration revision is what runs record.
        assertThat(run.getPlanRevision()).isEqualTo(1);
        JobPlan changed;
        try (var system = TenantContext.system("test")) {
            changed = first.registerManaged("fixture", plan, Map.of("revision", 2));
        }
        assertThat(changed.getConfigRevision()).isEqualTo(2);
        first.releaseNode("one"); var resumed = second.claim("default", "two");
        assertThat(resumed.getAttempts()).isEqualTo(1); assertThat(resumed.getPlanRevision()).isEqualTo(run.getPlanRevision());
        assertThat(JobService.parameters(resumed.getParametersJson())).containsEntry("revision", 1);
    }
    @Test void retentionAppliesDaysAndMinimumHistoryWithoutStarvation() {
        var kept = plan("test.platform", null, JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ZERO));
        // The oldest retained plan must not block newer eligible queue history.
        global(() -> {
            for (int i = 0; i < 25; i++) insertHistory(kept.getId(), Instant.now().minus(Duration.ofDays(200)).plusSeconds(i));
            for (int i = 0; i < 220; i++) insertHistory(null, Instant.now().minus(Duration.ofDays(100)).plusSeconds(i));
            insertHistory(null, Instant.now().minus(Duration.ofDays(10))); return null;
        });
        assertThat(first.purgeExpired()).isEqualTo(225);
        global(() -> { assertThat(runs.query(Criteria.of().eq("planId", kept.getId()), PageRequest.of(1, 100))).hasSize(20);
            assertThat(runs.query(Criteria.of(), PageRequest.of(1, 1000))).hasSize(21); return null; });
    }
    @Test void retentionUsesPerTypeOverrideAndDefaultForUnknownTypes() {
        global(() -> {
            insertHistory(null, Instant.now().minus(Duration.ofDays(40)));
            var tenant = history(null, Instant.now().minus(Duration.ofDays(40)));
            tenant.setJobType("test.tenant"); tenant.setScope("TENANT"); tenant.setTenantId("tenant-a"); runs.insert(tenant);
            var unknown = history(null, Instant.now().minus(Duration.ofDays(100)));
            unknown.setJobType("removed.type"); runs.insert(unknown); return null;
        });
        assertThat(first.purgeExpired()).isEqualTo(2);
        global(() -> { assertThat(runs.query(Criteria.of(), PageRequest.of(1, 100)))
                .extracting(JobRun::getJobType).containsExactly("test.platform"); return null; });
    }
    @Test void exhaustedTakeoverFailsAndCooperativeCancellationInvalidatesTokens() {
        var queued = queued("test.platform", null, "v1"); var run = first.claim("default", "one");
        global(() -> { db.execute("update platform_job_run set attempts=3,lease_until=clock_timestamp()-interval '1 second' where id=?", run.getId()); return null; });
        assertThat(second.claim("default", "two")).isNull();
        global(() -> { assertThat(runs.findById(queued.getId()).getStatus()).isEqualTo("FAILED"); return null; });
        var pending = queued("test.platform", null, "v2"); first.cancel(pending.getId());
        global(() -> { assertThat(runs.findById(pending.getId()).getStatus()).isEqualTo("CANCELLED"); return null; });
    }
    private void insertHistory(String plan, Instant finished) { runs.insert(history(plan, finished)); }
    private JobRun history(String plan, Instant finished) {
        var row = new JobRun(); row.setJobType("test.platform"); row.setScope("PLATFORM"); row.setPlanId(plan);
        row.setStatus("SUCCEEDED"); row.setAttempts(1); row.setFinishedAt(finished); row.setAvailableAt(finished);
        row.setMutexKey("history"); row.setParametersJson("{}"); row.setChannel("default"); row.setIdentityMode("SYSTEM");
        row.setTriggerType("QUEUE"); row.setCancelRequested(false); row.setProgress(100); row.setTraceId(net.ximatai.muyun.spring.common.id.Ids.newId());
        net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareInsert(row, finished); return row;
    }
    private JobPlan plan(String type, String tenant, JobSchedule schedule) {
        try (var context = TenantContext.system("test")) {
            var row = new JobPlan(); row.setJobType(type); row.setTenantId(tenant); row.setObjectKey("fixture"); schedule.apply(row);
            return first.registerManaged("fixture", row, Map.of("revision", 1));
        }
    }
    private JobRun queued(String type, String tenant, String key) {
        try (var context = tenant == null ? TenantContext.system("test") : TenantContext.use(tenant)) {
            return first.enqueue(type, tenant, Map.of("revision", key), key);
        }
    }
    private void expire(String id) { global(() -> { db.execute("update platform_job_run set lease_until=clock_timestamp()-interval '1 second' where id=?", id); return null; }); }
    private <T> T global(Supplier<T> work) {
        try (var context = TenantContext.system("test"); var bypass = TenantContext.bypassTenantFilter("test")) { return work.get(); }
    }
    private static <T> List<T> together(Supplier<T> left, Supplier<T> right) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { await(gate); return left.get(); }); var b = pool.submit(() -> { await(gate); return right.get(); });
            gate.countDown(); return Arrays.asList(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally { gate.countDown(); pool.shutdownNow(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = JobPlanDao.class)
    static class Application {
        @Bean net.ximatai.muyun.database.spring.boot.JdbiConfigurer bigIntegerJdbiConfigurer() {
            // Production registers this in the starter; repository tests assemble the platform without it.
            return jdbi -> jdbi.registerArgument(new org.jdbi.v3.core.argument.AbstractArgumentFactory<java.math.BigInteger>(java.sql.Types.BIGINT) {
                @Override
                protected org.jdbi.v3.core.argument.Argument build(java.math.BigInteger value, org.jdbi.v3.core.config.ConfigRegistry config) {
                    return (position, statement, context) -> statement.setLong(position, value.longValueExact());
                }
            });
        }
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
    }
}
