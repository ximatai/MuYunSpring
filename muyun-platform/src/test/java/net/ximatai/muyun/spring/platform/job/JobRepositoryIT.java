package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.platform.notification.*;
import net.ximatai.muyun.spring.platform.workflow.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.*;
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
    @Autowired ScheduledActionPlanDao actionPlans;
    @Autowired PlatformModuleDao moduleRows;
    @Autowired PlatformModuleActionDao moduleActionRows;
    @Autowired WorkflowNodeInstanceDao nodes;
    @Autowired WorkflowInstanceDao instances;
    @Autowired WorkflowEventDao events;
    @Autowired IDatabaseOperations<?> db;
    @Autowired PlatformTransactionManager manager;
    private JobService first, second;
    private TransactionTemplate tx;
    private java.util.function.Function<JobContext, JobResult> execution;
    private final AtomicInteger executions = new AtomicInteger();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); executions.set(0); execution = null;
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("access", (JobManagementAccess) tenant -> { });
        beans.registerSingleton("recipients", (JobFailureRecipientResolver) tenant -> BusinessNotificationRecipients.none());
        var registry = new JobRegistry(List.of(handler("test.platform", JobScope.PLATFORM), handler("test.tenant", JobScope.TENANT)));
        first = service(registry, beans); second = service(registry, beans);
        global(() -> { db.execute("delete from platform_job_run"); db.execute("delete from platform_job_plan");
            db.execute("create table if not exists job_fencing_fixture (id varchar primary key, value integer)");
            db.execute("delete from job_fencing_fixture");
            db.execute("delete from platform_workflow_event"); db.execute("delete from platform_workflow_node_instance");
            db.execute("delete from platform_workflow_instance"); return null; });
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
                if (execution != null) return execution.apply(context);
                context.transaction(() -> { executions.incrementAndGet(); return null; });
                return JobResult.success("COMPLETE", Map.of("processed", 1L));
            }
        };
    }
    @Test void smallScheduledActionPagePushesVisibilityAndFiltersIntoPostgres() {
        String tenantId = "visibility-it";
        var beans = new DefaultListableBeanFactory();
        var modules = mock(PlatformModuleService.class);
        when(modules.select(anyString())).thenAnswer(call -> moduleRows.findById(call.getArgument(0)));
        beans.registerSingleton("dataAuth", mock(ModuleActionDataAuthResolver.class));
        var catalog = spy(new PlatformModuleActionService(moduleActionRows, modules, Optional.empty(), Optional.empty(),
                beans.getBeanProvider(ModuleActionDataAuthResolver.class)));
        var permissions = mock(ActionExecutionPolicyService.class);
        when(permissions.authorize(any())).thenAnswer(call -> {
            var context = call.getArgument(0, ActionExecutionContext.class);
            assertThat(context.moduleAlias()).isEqualTo("test.target");
            assertThat(TenantContext.currentTenantId()).contains(tenantId);
            if (!context.actionCode().equals("allowed"))
                throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("denied target");
            return ActionAuthorizationResult.allowed(context, "ROLE_GRANTED");
        });
        var invoker = spy(new ModuleActionInvoker(modules, catalog, permissions,
                beans.getBeanProvider(net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService.class),
                beans.getBeanProvider(ScheduledModuleAction.class), event -> {}));
        var dao = spy(actionPlans);
        var topic = new ScheduledActionPlanService(dao, first, invoker,
                beans.getBeanProvider(net.ximatai.muyun.spring.common.identity.TrustedExecutionIdentityResolver.class),
                (module, action) -> false,
                tenant -> { throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("ordinary"); },
                new com.fasterxml.jackson.databind.ObjectMapper(), tenant -> {});
        global(() -> {
            db.execute("delete from platform_scheduled_action_plan");
            db.execute("delete from platform_module_action");
            db.execute("delete from platform_module");
            for (String moduleAlias : List.of("test.target", "test.foreign")) {
                var module = new PlatformModule(); module.setAlias(moduleAlias); module.setApplicationAlias("test");
                module.setTitle(moduleAlias); module.setModuleKind(ModuleKind.DYNAMIC);
                module.setTenantId(moduleAlias.equals("test.target") ? tenantId : "other-tenant");
                EntityLifecycle.prepareInsert(module, Instant.now()); moduleRows.insert(module);
            }
            for (String code : List.of("allowed", "denied")) insertScheduledCatalogAction("test.target", code);
            // Another tenant cannot exhaust this tenant's visibility budget.
            for (int index = 0; index < 300; index++) insertScheduledCatalogAction("test.foreign", "action" + index);
            // Only two of these 3000 plans should be visible and match the requested filter.
            db.execute("""
                    insert into platform_scheduled_action_plan
                    (id, tenant_id, module_alias, action_code, job_plan_id, enabled, arguments_json, identity_mode, schedule_kind, created_at, updated_at, version)
                    select 'visibility-' || n, ?, 'test.target',
                           case when n<=2 then 'allowed' else 'denied' end, 'job-' || n, true, '{}', 'INITIATOR', 'FIXED_DELAY',
                           current_timestamp, current_timestamp, 0 from generate_series(1,3000) n
                    """, tenantId);
            db.execute("update platform_scheduled_action_plan set arguments_json='invalid frozen parameters' where id='visibility-1'");
            db.execute("""
                    insert into platform_scheduled_action_plan
                    (id, tenant_id, module_alias, action_code, job_plan_id, enabled, arguments_json, identity_mode, schedule_kind, created_at, updated_at, version)
                    values ('foreign-visible','other-tenant','test.target','allowed','foreign-job',true,'{}','INITIATOR','FIXED_DELAY',current_timestamp,current_timestamp,0),
                           ('disabled-visible',?,'test.target','allowed','disabled-job',false,'{}','INITIATOR','FIXED_DELAY',current_timestamp,current_timestamp,0)
                    """, tenantId);
            return null;
        });
        try (var user = net.ximatai.muyun.spring.common.identity.CurrentUserContext.use(
                net.ximatai.muyun.spring.common.identity.CurrentUser.tenantUser("reader", "reader", tenantId));
             var tenant = TenantContext.use(tenantId)) {
            var firstPage = topic.pageQuery(Criteria.of().eq("enabled", true), new PageRequest(0, 1), Sort.asc("id"));
            assertThat(firstPage.getTotal()).isEqualTo(2);
            assertThat(firstPage.getRecords()).extracting(ScheduledActionPlan::getId).containsExactly("visibility-1");
            var secondPage = topic.pageQuery(Criteria.of().eq("enabled", true), new PageRequest(1, 1), Sort.asc("id"));
            assertThat(secondPage.getTotal()).isEqualTo(2);
            assertThat(secondPage.getRecords()).extracting(ScheduledActionPlan::getId).containsExactly("visibility-2");
            doThrow(new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("all targets denied"))
                    .when(permissions).authorize(any());
            var empty = topic.pageQuery(Criteria.of(), new PageRequest(0, 1));
            assertThat(empty.getTotal()).isZero();
            assertThat(empty.getRecords()).isEmpty();
        }
        verify(dao, never()).query(any(Criteria.class), org.mockito.ArgumentMatchers.<PageRequest>argThat(page -> page.getLimit() > 1), any(Sort[].class));
        verify(invoker, times(3)).authorizedScheduledTargets();
        verify(catalog, atLeastOnce()).list(any(Criteria.class), org.mockito.ArgumentMatchers.<PageRequest>argThat(page -> page.getLimit() <= 100), any(Sort.class));
        verify(invoker, never()).requireAuthorized(anyString(), anyString(), anyMap());
    }

    private void insertScheduledCatalogAction(String module, String code) {
        var action = new PlatformModuleAction(); action.setModuleAlias(module); action.setActionCode(code);
        action.setTitle(code); action.setActionLevel(EntityActionLevel.LIST);
        action.setExecutorType(EntityActionExecutorType.SERVICE); action.setExecutorKey("fixture");
        action.setSchedulable(true); EntityLifecycle.prepareInsert(action, Instant.now()); moduleActionRows.insert(action);
    }

    @Test void standardPagePayloadSchedulesAndExecutesInSelectedTenant() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        // recordMutationPayload strips tenantId; only the business execution scope crosses the wire.
        var row = json.readValue("""
                {"executionTenantId":"demo","identityMode":"SYSTEM","moduleAlias":"test.target",
                 "actionCode":"execute","initialDelayMillis":0}
                """, ScheduledActionPlan.class);
        var beans = new DefaultListableBeanFactory();
        var invoker = mock(net.ximatai.muyun.spring.platform.module.ModuleActionInvoker.class);
        beans.registerSingleton("actions", invoker);
        beans.registerSingleton("access", (JobManagementAccess) tenant -> { });
        var handler = new ModuleActionJob(JobScope.TENANT,
                beans.getBeanProvider(net.ximatai.muyun.spring.platform.module.ModuleActionInvoker.class), actionPlans,
                (module, action) -> true,
                beans.getBeanProvider(net.ximatai.muyun.spring.common.identity.TrustedExecutionIdentityResolver.class), json);
        var scheduler = service(new JobRegistry(List.of(handler)), beans);
        var topic = new ScheduledActionPlanService(actionPlans, scheduler, invoker,
                beans.getBeanProvider(net.ximatai.muyun.spring.common.identity.TrustedExecutionIdentityResolver.class),
                (module, action) -> true, tenant -> { }, json, tenant -> {
                    if (!"demo".equals(tenant)) throw new IllegalArgumentException("Invalid tenant");
                });
        doAnswer(call -> {
            assertThat(TenantContext.currentTenantId()).contains("demo");
            executions.incrementAndGet(); return null;
        }).when(invoker).invoke("test.target", "execute", Map.of());
        try (var user = net.ximatai.muyun.spring.common.identity.CurrentUserContext.use(
                net.ximatai.muyun.spring.common.identity.CurrentUser.systemUser("admin", "admin"));
             var scope = TenantContext.system("configure page plan")) {
            tx.execute(status -> topic.insert(row));
            assertThat(actionPlans.findById(row.getId()).getExecutionTenantId()).isEqualTo("demo");
            assertThat(actionPlans.findById(row.getId()).getTenantId()).isEqualTo("demo");
            assertThat(plans.findById(row.getJobPlanId()).getTenantId()).isEqualTo("demo");
            var invalid = json.readValue("{\"executionTenantId\":\"missing\"}", ScheduledActionPlan.class);
            assertThatThrownBy(() -> tx.execute(status -> topic.insert(invalid))).hasMessageContaining("Invalid tenant");
        }
        scheduler.scan();
        assertThat(scheduler.runOne("default", "page-regression")).isTrue();
        assertThat(executions.get()).isEqualTo(1);
        global(() -> {
            var run = runs.query(Criteria.of().eq("planId", row.getJobPlanId()), PageRequest.of(1, 10)).getFirst();
            assertThat(run.getTenantId()).isEqualTo("demo");
            assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(run.getTriggerType()).isEqualTo("SCHEDULE");
            assertThat(run.getResultCode()).isEqualTo("ACTION_EXECUTED");
            return null;
        });
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
        var tenantClaims = List.of(first.claim("default", "tenant-owner-a"), second.claim("default", "tenant-owner-b"));
        assertThat(tenantClaims).extracting(JobRun::getId).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(tenantClaims).allSatisfy(run -> {
            assertThat(run.getTenantId()).isEqualTo(run.getId().equals(a.getId()) ? "tenant-a" : "tenant-b");
            assertThat(run.getStatus()).isEqualTo("RUNNING");
            assertThat(run.getLeaseToken()).isNotBlank(); assertThat(run.getLeaseUntil()).isAfter(run.getStartedAt());
            assertThat(run.getAttempts()).isEqualTo(1);
        });
        assertThat(tenantClaims).extracting(JobRun::getNodeId).containsExactly("tenant-owner-a", "tenant-owner-b");
        assertThat(tenantClaims).extracting(JobRun::getLeaseToken).doesNotHaveDuplicates();
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
    @Test void administratorUpdatesRequireConfigRevisionButRuntimeStateDoesNotConflict() {
        first.registerDefaults();
        var request = global(() -> plans.query(Criteria.of(), PageRequest.of(1, 1)).getFirst());
        Integer revision = request.getConfigRevision();
        request.setConfigRevision(null);
        assertThatThrownBy(() -> first.configure(request)).isInstanceOf(net.ximatai.muyun.spring.ability.OptimisticLockException.class);
        request.setConfigRevision(revision);
        first.runNow(request.getId()); // Updates the storage version, not the configuration revision.
        request.setIntervalMillis(7200000L);
        var updated = first.configure(request);
        assertThat(updated.getConfigRevision()).isEqualTo(revision + 1);
        assertThatThrownBy(() -> first.configure(request)).isInstanceOf(net.ximatai.muyun.spring.ability.OptimisticLockException.class);
        global(() -> {
            assertThat(plans.findById(request.getId()).getIntervalMillis()).isEqualTo(7200000L); return null;
        });
    }
    @ParameterizedTest @ValueSource(ints = {1, 3})
    void maximumRuntimeTransitionsReleaseLeaseAndMutexAndRejectOldToken(int attempts) {
        var queued = queued("test.platform", null, "timeout"); var leased = first.claim("default", "owner");
        global(() -> { db.execute("update platform_job_run set started_at=clock_timestamp()-interval '6 minutes', attempts=? where id=?", attempts, queued.getId()); return null; });
        first.renewNode("owner");
        global(() -> {
            var row = runs.findById(queued.getId());
            assertThat(row.getStatus()).isEqualTo(attempts == 1 ? "RETRY" : "FAILED");
            assertThat(row.getResultCode()).isEqualTo("MAXIMUM_RUNTIME_EXCEEDED");
            assertThat(row.getLeaseToken()).isNull(); assertThat(row.getLeaseUntil()).isNull(); assertThat(row.getNodeId()).isNull();
            assertThat(row.getFinishedAt() != null).isEqualTo(attempts == 3);
            if (attempts == 1) db.execute("update platform_job_run set available_at=clock_timestamp()+interval '1 day' where id=?", row.getId());
            return null;
        });
        assertThatThrownBy(() -> first.protectedTransaction(leased.getId(), leased.getLeaseToken(), () -> { }, () -> {
            db.execute("insert into job_fencing_fixture values ('stale',1)"); return null;
        })).isInstanceOf(JobFailure.class).hasMessageContaining("LEASE_LOST");
        var independent = queued("test.platform", null, "after-timeout");
        assertThat(second.claim("default", "next-owner").getId()).isEqualTo(independent.getId());
        assertThat(db.query("select * from job_fencing_fixture")).isEmpty();
    }
    @Test void protectedTransactionLockTimeoutRollsBackBusinessWritesAndRetriesOnUsableConnection() throws Exception {
        global(() -> { db.execute("insert into job_fencing_fixture values ('locked',0)"); return null; });
        var pool = Executors.newSingleThreadExecutor(); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocker = pool.submit(() -> tx.execute(status -> {
            db.execute("update job_fencing_fixture set value=1 where id='locked'"); held.countDown(); await(release); return null;
        }));
        try {
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var timedOut = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
            var connectionPid = new java.util.concurrent.atomic.AtomicReference<Object>();
            execution = context -> {
                try {
                    context.transaction(() -> {
                        connectionPid.set(db.row("select pg_backend_pid() as pid").get("pid"));
                        db.query("select set_config('lock_timeout','100',true)");
                        db.execute("insert into job_fencing_fixture values ('rolled-back',1)");
                        db.execute("update job_fencing_fixture set value=2 where id='locked'"); return null;
                    });
                } catch (RuntimeException failure) { timedOut.set(failure); throw failure; }
                return JobResult.success("COMPLETE", Map.of());
            };
            var queued = queued("test.platform", null, "timeout");
            assertThat(first.runOne("default", "owner")).isTrue();
            assertThat(timedOut.get()).hasRootCauseInstanceOf(java.sql.SQLException.class);
            Throwable cause = timedOut.get();
            while (cause.getCause() != null) cause = cause.getCause();
            assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("55P03");
            global(() -> {
                var run = runs.findById(queued.getId());
                assertThat(run.getStatus()).isEqualTo("RETRY"); assertThat(run.getResultCode()).isEqualTo("EXECUTION_FAILED");
                assertThat(run.getLeaseToken()).isNull(); assertThat(run.getLeaseUntil()).isNull();
                assertThat(db.query("select * from job_fencing_fixture where id='rolled-back'")).isEmpty(); return null;
            });
            // Hikari returns this thread's physical connection; verify it is reusable after rollback.
            var recoveredPid = tx.execute(status -> {
                var pid = db.row("select pg_backend_pid() as pid").get("pid");
                db.execute("insert into job_fencing_fixture values ('usable',3)"); return pid;
            });
            assertThat(recoveredPid).isEqualTo(connectionPid.get());
            assertThat(db.query("select * from job_fencing_fixture where id='usable'")).hasSize(1);
        } finally { release.countDown(); blocker.get(5, TimeUnit.SECONDS); pool.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(strings = {"complete", "cancel", "lease", "failure"})
    void workflowScanCommitsFiniteBatchesAndFencesSubsequentWrites(String mode) {
        global(() -> {
            var instance = new WorkflowInstance(); instance.setId("workflow-fixture"); instance.setDefinitionId("definition");
            instance.setWorkflowVersionId("version"); instance.setVersionNo(1); instance.setModuleAlias("test.workflow");
            instance.setRecordId("record"); instance.setSnapshotText("{}"); instance.setStartedAt(Instant.now());
            net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareInsert(instance, Instant.now()); instances.insert(instance);
            for (int index = 0; index < 205; index++) {
                var node = new WorkflowNodeInstance(); node.setId("node-%04d".formatted(index)); node.setInstanceId(instance.getId());
                node.setNodeKey("approval"); node.setNodeRunId("approval:" + index); node.setNodeType(WorkflowNodeType.APPROVAL);
                node.setNodeStatus(WorkflowNodeStatus.ACTIVE); node.setActivatedAt(Instant.now().minusSeconds(600));
                node.setOvertimeDurationMinutes(1); node.setOvertimeStatus(WorkflowOvertimeStatus.NORMAL);
                net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareInsert(node, Instant.now());
                node.setUpdatedBy("original-editor"); nodes.insert(node);
            }
            return null;
        });
        var publisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        if (mode.equals("failure")) doAnswer(call -> {
            var fact = (WorkflowEventWriter.Persisted) call.getArgument(0);
            if (fact.event().getNodeInstanceId().equals("node-0202")) throw new IllegalStateException("batch failed");
            return null;
        }).when(publisher).publishEvent(any(Object.class));
        var scan = spy(new WorkflowNodeOvertimeScanService(nodes, instances, new WorkflowEventWriter(events, publisher), new WorkflowRuntimeEventFactory()));
        var batches = new AtomicInteger();
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var batch = call.callRealMethod();
            if (batches.incrementAndGet() == 1) {
                if (mode.equals("cancel")) db.execute("update platform_job_run set cancel_requested=true where status='RUNNING'");
                if (mode.equals("lease")) db.execute("update platform_job_run set lease_until=clock_timestamp()-interval '1 second' where status='RUNNING'");
            }
            return batch;
        }).when(scan).scanBatch(any(), org.mockito.ArgumentMatchers.nullable(String.class));
        var job = new WorkflowNodeOvertimeJob(scan, 300000, 60000);
        execution = job::execute;
        var queued = queued("test.platform", null, "workflow"); first.runOne("default", "owner");
        global(() -> {
            int committed = mode.equals("complete") ? 205 : 200;
            assertThat(nodes.query(Criteria.of().eq("overtimeStatus", WorkflowOvertimeStatus.OVERDUE), PageRequest.of(1, 300)))
                    .hasSize(committed).allSatisfy(node -> assertThat(node.getUpdatedBy()).isEqualTo("original-editor"));
            assertThat(events.query(Criteria.of(), PageRequest.of(1, 300))).hasSize(committed)
                    .allSatisfy(event -> assertThat(event.getOperatorId()).isEqualTo("SYSTEM"));
            assertThat(runs.findById(queued.getId()).getStatus()).isEqualTo(switch (mode) {
                case "complete" -> "SUCCEEDED"; case "cancel" -> "CANCELLED"; case "failure" -> "RETRY"; default -> "RUNNING";
            });
            if (!mode.equals("complete")) assertThat(nodes.findById("node-0200").getOvertimeStatus()).isEqualTo(WorkflowOvertimeStatus.NORMAL);
            return null;
        });
        assertThat(batches).hasValue(mode.equals("complete") ? 2 : 1);
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
    @EnableMuYunRepositories(basePackageClasses = {JobPlanDao.class, WorkflowNodeInstanceDao.class, PlatformModuleDao.class})
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
