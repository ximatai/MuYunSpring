package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.platform.workflow.*;
import net.ximatai.muyun.spring.platform.logging.*;
import net.ximatai.muyun.spring.ability.logging.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationDelivery;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Real storage and queue contracts for the migrated maintenance work. */
@SpringBootTest(classes = JobMaintenanceIT.Application.class)
class JobMaintenanceIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired JobService jobs;
    @Autowired JobPlanDao plans;
    @Autowired JobRunDao runs;
    @Autowired DataSource source;
    @Autowired BusinessLogRetentionService retention;
    @Autowired WorkflowNodeOvertimeScanService scans;
    @Autowired WorkflowNodeInstanceDao nodes;
    @Autowired WorkflowInstanceDao instances;
    @Autowired WorkflowEventDao events;

    @BeforeEach void reset() {
        TenantContext.clear();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("delete from platform_job_run");
        jdbc.update("delete from platform_job_plan");
        jdbc.update("delete from platform_workflow_event");
        jdbc.update("delete from platform_workflow_node_instance");
        jdbc.update("delete from platform_workflow_instance");
        jdbc.update("delete from muyun_log.business_log_event");
        jdbc.update("delete from muyun_log.business_log_retention_policy");
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void logCleanupMapsPartialAndCompleteAndKeepsDisabledPoliciesAndFutureEvents() {
        enableLogin();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                insert into muyun_log.business_log_event(event_id,event_type,occurred_at,captured_at,trace_id,details_json)
                select 'expired-' || n, 'LOGIN', now() - interval '100 days', now(), 'trace', '{}'::jsonb
                from generate_series(1,3) n
                """);
        jdbc.update("""
                insert into muyun_log.business_log_event(event_id,event_type,occurred_at,captured_at,trace_id,details_json)
                values ('future','LOGIN',now() + interval '1 day',now(),'trace','{}'),
                       ('disabled','ACTION',now() - interval '100 days',now(),'trace','{}')
                """);
        var first = execute("platform.business-log-retention");
        assertThat(first.getStatus()).isEqualTo("PARTIAL");
        assertThat(first.getResultCode()).isEqualTo("BATCH_LIMIT_REACHED");
        assertThat(first.getStatisticsJson()).contains("\"LOGIN_deleted\":2", "\"LOGIN_batches\":1");
        assertThat(plans.findById(first.getPlanId()).getConsecutiveFailures()).isZero();
        var second = execute("platform.business-log-retention");
        assertThat(second.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(second.getResultCode()).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForList("select event_id from muyun_log.business_log_event", String.class))
                .containsExactlyInAnyOrder("future", "disabled");
        assertThat(Duration.between(second.getFinishedAt(), plans.findById(second.getPlanId()).getNextFireAt()))
                .isEqualTo(Duration.ofHours(24));
    }

    @Test void anotherInstanceHoldingTheStorageLockIsASuccessfulSkip() throws Exception {
        enableLogin();
        try (var connection = source.getConnection();
             var lock = connection.prepareStatement("select pg_advisory_lock(?)")) {
            lock.setLong(1, 0x4D_55_59_55_4E_4C_4F_47L); lock.execute();
            try {
                var run = execute("platform.business-log-retention");
                assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
                assertThat(run.getResultCode()).isEqualTo("SKIPPED_ALREADY_RUNNING");
                assertThat(run.getStatisticsJson()).contains("\"LOGIN_ALREADY_RUNNING\":1", "\"skipped\":1");
                assertThat(plans.findById(run.getPlanId()).getConsecutiveFailures()).isZero();
            } finally {
                try (var unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                    unlock.setLong(1, 0x4D_55_59_55_4E_4C_4F_47L); unlock.execute();
                }
            }
        }
    }

    @Test void workflowJobAndBothPreviousContextsScanTheSameTenantsAndWriteTheSameSystemEvents() {
        var global = fixture(null, true);
        var tenantA = fixture("tenant-a", false);
        var tenantB = fixture("tenant-b", true);
        // Raw DAO scanning has always included all tenant partitions, unlike workflow read facades.
        var before = scans.scan(null);
        assertThat(before).isEqualTo(new WorkflowNodeOvertimeScanResult(3, 1, 2));
        var original = eventFacts();
        resetScan();
        try (var ignored = TenantContext.system("compare previous scan scope")) {
            assertThat(scans.scan(null)).isEqualTo(before);
        }
        assertThat(eventFacts()).containsExactlyInAnyOrderElementsOf(original);
        resetScan();
        var run = execute("platform.workflow-overtime");
        assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(run.getStatisticsJson()).contains("\"scanned\":3", "\"warned\":1", "\"overdue\":2", "\"updated\":3");
        assertThat(eventFacts()).containsExactlyInAnyOrderElementsOf(original);
        assertThat(nodes.findById(global).getOvertimeStatus()).isEqualTo(WorkflowOvertimeStatus.OVERDUE);
        assertThat(nodes.findById(tenantA).getOvertimeStatus()).isEqualTo(WorkflowOvertimeStatus.WARNED);
        assertThat(nodes.findById(tenantB).getOvertimeStatus()).isEqualTo(WorkflowOvertimeStatus.OVERDUE);
        for (String id : List.of(global, tenantA, tenantB)) {
            assertThat(nodes.findById(id).getUpdatedBy()).isEqualTo("original-editor");
        }
        assertThat(Duration.between(run.getFinishedAt(), plans.findById(run.getPlanId()).getNextFireAt()))
                .isEqualTo(Duration.ofMinutes(5));
        var again = execute("platform.workflow-overtime");
        assertThat(again.getStatisticsJson()).contains("\"updated\":0");
        assertThat(eventFacts()).hasSize(3);
        assertThat(TenantContext.hasContext()).isFalse();
    }

    @Test void defaultRegistrationKeepsPersistedScheduleAndAdministratorPauseAcrossDeploymentChanges() {
        jobs.registerDefaults();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("update platform_job_plan set enabled = false, interval_millis = 7200000, config_revision = 2");
        jobs.registerDefaults();
        assertThat(plans.query(Criteria.of(), PageRequest.of(1, 10))).hasSize(2).allSatisfy(plan -> {
            assertThat(plan.getEnabled()).isFalse();
            assertThat(plan.getIntervalMillis()).isEqualTo(7200000L);
            assertThat(plan.getConfigRevision()).isEqualTo(2);
        });
    }

    private void enableLogin() {
        retention.updatePolicy(BusinessLogEventType.LOGIN, true, 30, 0, "admin", BusinessLogReadScope.platform());
    }
    private JobRun execute(String type) {
        jobs.registerDefaults();
        var plan = plans.query(Criteria.of().eq("jobType", type), PageRequest.of(1, 1)).getFirst();
        assertThat(plan.getInitialDelayMillis()).isEqualTo(60000L);
        new JdbcTemplate(source).update("update platform_job_plan set next_fire_at = now() - interval '1 minute' where id = ?", plan.getId());
        jobs.scan();
        assertThat(jobs.runOne("default", "maintenance-node")).isTrue();
        return runs.query(Criteria.of().eq("planId", plan.getId()), PageRequest.of(1, 100))
                .stream().filter(run -> run.getFinishedAt() != null)
                .max(java.util.Comparator.comparing(JobRun::getFinishedAt)).orElseThrow();
    }
    private void resetScan() {
        var jdbc = new JdbcTemplate(source);
        jdbc.update("delete from platform_workflow_event");
        jdbc.update("update platform_workflow_node_instance set overtime_status = 'NORMAL', warned_at = null, overdue_at = null");
    }
    private List<Map<String, Object>> eventFacts() {
        return new JdbcTemplate(source).queryForList("""
                select tenant_id, instance_id, node_instance_id, event_type, action_code, operator_id, message,
                       payload_text::jsonb - 'triggeredAt' as payload
                from platform_workflow_event
                """);
    }
    private String fixture(String tenant, boolean overdue) {
        var instance = new WorkflowInstance(); instance.setTenantId(tenant); instance.setDefinitionId("definition");
        instance.setWorkflowVersionId("version"); instance.setVersionNo(1); instance.setModuleAlias("test.overtime");
        instance.setSnapshotText("{}"); instance.setRecordId("record"); instance.setStartedAt(Instant.now());
        EntityLifecycle.prepareInsert(instance, Instant.now()); instances.insert(instance);
        var node = new WorkflowNodeInstance(); node.setTenantId(tenant); node.setInstanceId(instance.getId());
        node.setNodeKey("approval"); node.setNodeRunId("run"); node.setNodeType(WorkflowNodeType.APPROVAL);
        node.setNodeStatus(WorkflowNodeStatus.ACTIVE); node.setActivatedAt(Instant.now().minusSeconds(3600));
        node.setWarningDurationMinutes(10); node.setOvertimeDurationMinutes(overdue ? 20 : 120);
        node.setOvertimeStatus(WorkflowOvertimeStatus.NORMAL); node.setUpdatedBy("original-editor");
        EntityLifecycle.prepareInsert(node, Instant.now());
        nodes.insert(node); return node.getId();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = {JobPlanDao.class, WorkflowNodeInstanceDao.class})
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
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean PostgresBusinessLogStore logStore(DataSource source) {
            var store = new PostgresBusinessLogStore(source); store.run(); return store;
        }
        @Bean BusinessLogRetentionService retention(PostgresBusinessLogStore store) {
            return new BusinessLogRetentionService(store, store);
        }
        @Bean WorkflowEventWriter eventWriter(WorkflowEventDao events, org.springframework.context.ApplicationEventPublisher publisher) {
            return new WorkflowEventWriter(events, publisher);
        }
        @Bean WorkflowNodeOvertimeScanService scanService(WorkflowNodeInstanceDao nodes, WorkflowInstanceDao instances,
                                                        WorkflowEventWriter writer) {
            return new WorkflowNodeOvertimeScanService(nodes, instances, writer, new WorkflowRuntimeEventFactory());
        }
        @Bean JobRegistry registry(BusinessLogRetentionService retention, WorkflowNodeOvertimeScanService scan) {
            return new JobRegistry(List.of(new BusinessLogRetentionJob(retention,
                    new BusinessLogRetentionExecutionLimits(2, 1),
                    JobSchedule.fixedDelay(Duration.ofHours(24), Duration.ofMinutes(1))),
                    new WorkflowNodeOvertimeJob(scan, 300000, 60000)));
        }
        @Bean JobService jobs(JobPlanDao plans, JobRunDao runs, IDatabaseOperations<?> database,
                              PlatformTransactionManager transactions, JobRegistry registry,
                              ObjectProvider<JobFailureRecipientResolver> recipients,
                              ObjectProvider<BusinessNotificationDelivery> notifications,
                              ObjectProvider<JobManagementAccess> access) {
            return new JobService(plans, runs, database, transactions, registry, recipients, notifications, access,
                    Duration.ofMinutes(1), 90);
        }
    }
}
