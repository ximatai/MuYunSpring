package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.notification.*;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobServiceContractTest {
    private final Plans plans = new Plans();
    private final Runs runs = new Runs();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-25T12:00:00Z"));
    private final List<BusinessNotification> alerts = new ArrayList<>();
    private final IDatabaseOperations<?> db = mock(IDatabaseOperations.class);
    private Function<JobContext, JobResult> execute;
    private JobService service;

    @BeforeEach void setup() {
        when(db.row(anyString())).thenAnswer(call -> Map.of("seconds", new java.math.BigDecimal(now.get().toEpochMilli()).movePointLeft(3)));
        when(db.query(startsWith("select r.id, r.mutex_key"), anyList())).thenAnswer(call ->
                runs.query(Criteria.of().in("status", List.of("PENDING", "RETRY", "RUNNING")), PageRequest.of(1, 100), Sort.asc("availableAt"), Sort.asc("id"))
                        .stream().map(run -> Map.<String, Object>of("id", run.getId(), "mutex_key", run.getMutexKey())).toList());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("access", (JobManagementAccess) tenant -> { });
        beans.registerSingleton("recipient", (JobFailureRecipientResolver) tenant -> new BusinessNotificationRecipients(false, List.of(), List.of(), List.of(), List.of(), List.of("admin")));
        beans.registerSingleton("notifications", (BusinessNotificationDelivery) alerts::add);
        var handler = new JobHandler() {
            public JobDefinition definition() {
                return new JobDefinition("test.contract", "test.name", JobScope.TENANT, null, "default", null,
                        Duration.ofMinutes(5), 2, Duration.ofSeconds(10), input -> "one", true, 3, true, null);
            }
            public JobResult execute(JobContext context) { return execute.apply(context); }
        };
        service = new JobService(plans, runs, db, new LocalTransactions(), new JobRegistry(List.of(handler)),
                beans.getBeanProvider(JobFailureRecipientResolver.class), beans.getBeanProvider(BusinessNotificationDelivery.class),
                beans.getBeanProvider(JobManagementAccess.class), Duration.ofMinutes(1), 90);
    }
    @Test void retryableFailuresRespectBackoffThenFinishWithSanitizedSummaryAndOneReminder() {
        execute = context -> { throw new IllegalStateException("secret provider response and credential"); };
        var row = enqueue("r:1"); assertThat(service.runOne("default", "node")).isTrue();
        assertThat(row.getStatus()).isEqualTo("RETRY"); assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getAvailableAt()).isEqualTo(now.get().plusSeconds(10));
        assertThat(alerts).isEmpty(); assertThat(service.runOne("default", "node")).isFalse();
        now.set(now.get().plusSeconds(10)); assertThat(service.runOne("default", "node")).isTrue();
        assertThat(row.getStatus()).isEqualTo("FAILED"); assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getErrorSummary()).isEqualTo("IllegalStateException:EXECUTION_FAILED");
        assertThat(alerts).hasSize(1); assertThat(alerts.getFirst().messageArgs()).containsEntry("runId", row.getId());
        assertThat(row.getLeaseToken()).isNull();
    }
    @Test void permanentFailureDoesNotRetryAndNormalPartialResultsAreSuccessful() {
        execute = context -> { throw new JobFailure("POLICY_DENIED", false); };
        var failed = enqueue("r:1"); service.runOne("default", "node");
        assertThat(failed.getStatus()).isEqualTo("FAILED"); assertThat(failed.getAttempts()).isEqualTo(1);
        execute = context -> JobResult.partial("BATCH_LIMIT_REACHED", Map.of("removed", 200L));
        var partial = enqueue("r:2"); service.runOne("default", "node");
        assertThat(partial.getStatus()).isEqualTo("PARTIAL"); assertThat(partial.getErrorSummary()).isNull();
        assertThat(partial.getStatisticsJson()).contains("200"); assertThat(alerts).hasSize(1);
    }
    @Test void executionDoesNotInheritRequestContextAndKeepsFrozenIdentityAndAuditCorrelation() {
        var original = CurrentUser.systemUser("request-user", "request-account");
        var input = new ArrayList<>(List.of("v1"));
        JobRun run;
        try (var tenant = TenantContext.use("tenant-a")) {
            run = service.enqueue("test.contract", "tenant-a", Map.of("values", input), "revision:1");
        }
        input.set(0, "v2");
        execute = context -> {
            assertThat(CurrentUserContext.currentUser()).isEmpty(); assertThat(ActionExecutionContextHolder.current()).isEmpty();
            assertThat(ActingContextHolder.current()).isEmpty(); assertThat(TenantContext.isSystem()).isFalse();
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.systemReason()).contains("后台作业 test.contract");
            assertThat(JobExecutionContext.current().orElseThrow().runId()).isEqualTo(context.runId());
            assertThat(context.parameters().get("values")).isEqualTo(List.of("v1"));
            return JobResult.success("COMPLETE", Map.of());
        };
        try (var user = CurrentUserContext.use(original); var tenant = TenantContext.use("request-tenant")) {
            service.runOne("default", "node");
            assertThat(CurrentUserContext.currentUser()).contains(original);
            assertThat(TenantContext.currentTenantId()).contains("request-tenant");
        }
        assertThat(run.getStatus()).isEqualTo("SUCCEEDED"); assertThat(JobExecutionContext.current()).isEmpty();
    }
    @Test void protectedTransactionAcquiresBusinessLockBeforeLeaseAndRejectsOldToken() {
        var row = enqueue("r:1"); var leased = service.claim("default", "node");
        var order = new ArrayList<String>();
        when(db.query(contains("for update"), any(Object[].class))).thenAnswer(call -> { order.add("lease"); return List.of(); });
        service.protectedTransaction(row.getId(), leased.getLeaseToken(), () -> order.add("business"), () -> { order.add("write"); return null; });
        assertThat(order).containsExactly("business", "lease", "write");
        now.set(now.get().plusSeconds(61));
        assertThatThrownBy(() -> service.protectedTransaction(row.getId(), leased.getLeaseToken(), () -> { }, () -> {
            fail("Expired token must not enter writes"); return null;
        })).isInstanceOf(JobFailure.class);
    }
    @Test void cancellationAndShutdownReleasePreserveAttemptsAndUnknownIdentityFailsClosed() {
        var pending = enqueue("r:1"); service.cancel(pending.getId()); assertThat(pending.getStatus()).isEqualTo("CANCELLED");
        var active = enqueue("r:2"); service.claim("default", "node"); service.releaseNode("node");
        assertThat(active.getAttempts()).isZero(); assertThat(active.getLeaseToken()).isNull();
        active.setIdentityMode("INITIATOR"); execute = context -> { fail("Reserved identity must never execute as system"); return null; };
        service.runOne("default", "next"); assertThat(active.getResultCode()).isEqualTo("IDENTITY_MODE_UNSUPPORTED");
        assertThat(active.getStatus()).isEqualTo("FAILED");
    }
    @Test void manualQueueRetryKeepsFrozenInputAndDeduplicationOwnership() {
        execute = context -> { throw new JobFailure("PERMANENT", false); };
        var original = enqueue("revision:1"); service.runOne("default", "node");
        var retry = service.retry(original.getId());
        assertThat(retry.getId()).isNotEqualTo(original.getId());
        assertThat(retry.getParametersJson()).isEqualTo(original.getParametersJson());
        assertThat(retry.getIdentityMode()).isEqualTo(original.getIdentityMode());
        assertThat(retry.getDeduplicationKey()).isEqualTo(original.getDeduplicationKey());
        assertThat(service.retry(original.getId()).getId()).isEqualTo(retry.getId());
        assertThat(enqueue("revision:1").getId()).isEqualTo(retry.getId());
        assertThat(original.getStatus()).isEqualTo("FAILED");
    }
    @Test void planOperationsPersistFailureStreakAndPartialSuccessResetsIt() {
        var request = new JobPlan(); request.setJobType("test.contract"); request.setTenantId("tenant-a"); request.setObjectKey("source");
        JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ZERO).apply(request);
        JobPlan plan;
        try (var tenant = TenantContext.use("tenant-a")) { plan = service.registerManaged("topic", request, Map.of("revision", 1)); }
        service.pause(plan.getId()); service.scan(); assertThat(runs.list(Criteria.of())).isEmpty();
        service.resume(plan.getId()); service.scan(); assertThat(plan.getLastStatus()).isEqualTo("PENDING");
        execute = context -> { throw new JobFailure("PERMANENT", false); };
        service.runOne("default", "node"); assertThat(plan.getConsecutiveFailures()).isEqualTo(1);
        for (int i = 0; i < 2; i++) { service.runNow(plan.getId()); service.runOne("default", "node"); }
        assertThat(plan.getConsecutiveFailures()).isEqualTo(3); assertThat(plan.getLastStatus()).isEqualTo("FAILED");
        assertThat(alerts).hasSize(3);
        execute = context -> JobResult.partial("BATCH_LIMIT_REACHED", Map.of());
        service.runNow(plan.getId()); service.runOne("default", "node");
        assertThat(plan.getConsecutiveFailures()).isZero(); assertThat(plan.getLastStatus()).isEqualTo("PARTIAL");
        assertThat(plan.getNextFireAt()).isEqualTo(now.get().plusSeconds(3600));
        assertThatThrownBy(() -> service.configure(plan)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
    }
    @Test void shutdownAfterClaimReleasesWithoutExecutingOrConsumingAttempt() {
        var row = enqueue("late:1");
        execute = context -> { fail("Shutdown must prevent late handler start"); return null; };
        assertThat(service.runOne("default", "late-owner", () -> false)).isFalse();
        assertThat(row.getStatus()).isEqualTo("PENDING"); assertThat(row.getAttempts()).isZero();
        assertThat(row.getLeaseToken()).isNull();
    }
    @Test void pausedPlansWithMissingTypesAreMarkedUnavailableAtRegistration() {
        var plan = new JobPlan(); plan.setId("gone"); plan.setJobType("removed.type"); plan.setEnabled(false); plan.setAvailable(true);
        plans.insert(plan); service.registerDefaults(); assertThat(plan.getAvailable()).isFalse();
    }
    @Test void maximumRuntimeRetriesThenFailsAndReleasesTheMutexAndToken() {
        var row = enqueue("timeout");
        for (int attempt = 1; attempt <= 2; attempt++) {
            var leased = service.claim("default", "owner");
            String token = leased.getLeaseToken();
            now.set(leased.getStartedAt().plusSeconds(300));
            service.renewNode("owner");
            assertThat(row.getStatus()).isEqualTo(attempt == 1 ? "RETRY" : "FAILED");
            assertThat(row.getResultCode()).isEqualTo("MAXIMUM_RUNTIME_EXCEEDED");
            assertThat(row.getLeaseToken()).isNull(); assertThat(row.getLeaseUntil()).isNull(); assertThat(row.getNodeId()).isNull();
            assertThatThrownBy(() -> service.protectedTransaction(row.getId(), token, () -> { }, () -> {
                fail("Expired owner cannot write"); return null;
            })).isInstanceOf(JobFailure.class).hasMessageContaining("LEASE_LOST");
            if (attempt == 1) now.set(row.getAvailableAt());
        }
        var next = enqueue("next");
        assertThat(service.claim("default", "next-owner").getId()).isEqualTo(next.getId());
    }
    @Test void cancellationFencesProtectedWritesEvenBeforeLeaseExpires() {
        var row = enqueue("cancel"); var leased = service.claim("default", "owner");
        service.cancel(row.getId());
        assertThatThrownBy(() -> service.protectedTransaction(row.getId(), leased.getLeaseToken(), () -> { }, () -> {
            fail("Cancelled run cannot write"); return null;
        })).isInstanceOf(JobFailure.class).hasMessageContaining("CANCELLED");
    }
    private JobRun enqueue(String key) {
        try (var tenant = TenantContext.use("tenant-a")) { return service.enqueue("test.contract", "tenant-a", Map.of(), key); }
    }
    static class Plans extends TestMemoryDao<JobPlan> implements JobPlanDao { }
    static class Runs extends TestMemoryDao<JobRun> implements JobRunDao { }
    static class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object transaction) { return TransactionSynchronizationManager.isActualTransactionActive(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
