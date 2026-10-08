package net.ximatai.muyun.spring.platform.workflow;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.model.standard.StandardApprovalEntity;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = WorkflowRecordDeletionRepositoryIT.Host.class)
class WorkflowRecordDeletionRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired WorkflowModuleSubmitService submitter;
    @Autowired WorkflowConcurrencyRepositoryIT.Pauses pauses;
    @Autowired WorkflowDefinitionDao definitions;
    @Autowired WorkflowVersionDao versions;
    @Autowired WorkflowNodeDefinitionDao nodeDefinitions;
    @Autowired WorkflowLinkDefinitionDao links;
    @Autowired StaticBusiness business;
    @Autowired StaticDao businessDao;
    @Autowired DynamicRecordRuntime runtime;
    @Autowired DynamicRecordService records;
    @Autowired DynamicSchemaService schema;
    @Autowired WorkflowInstanceDao instances;
    @Autowired WorkflowTaskDao tasks;
    @Autowired WorkflowHistoryInstanceDao histories;
    @Autowired WorkflowInstanceActionService actions;
    @Autowired WorkflowArchiveService archive;
    @Autowired WorkflowApprovalSummaryWriter summaries;
    @Autowired WorkflowRecordDeletionGuard guard;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired javax.sql.DataSource dataSource;
    private final String tenant = "workflow-deletion-owner";

    @BeforeEach void transactionHost() {
        var transactions = new TransactionTemplate(transactionManager);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            @Override public <T> T execute(java.util.function.Supplier<T> work) { return transactions.execute(status -> work.get()); }
            @Override public void lock(String scope, String key) {
                if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("transaction required");
                new JdbcTemplate(dataSource).queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                        scope.length() + ":" + scope + ":" + key);
            }
        });
        PlatformAbilityRuntime.configureRecordDeletionGuard(guard);
        pauses.reset();
        PlatformAbilityRuntime.configureDataScopeCriteriaService(() -> new AllowAllDataScopeCriteriaService() {
            @Override public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy, Criteria criteria, Optional<CurrentUser> user) {
                return DataScopeCriteriaResult.crossTenantUnrestricted(criteria);
            }
        });
        TenantContext.setTenantId(tenant);
        CurrentUserContext.use(CurrentUser.tenantUser("manager", "Manager", tenant));
    }
    @AfterEach void resetHost() {
        business.rejectSummary = false;
        pauses.release.countDown();
        PlatformAbilityRuntime.resetDataScopeCriteriaService();
        PlatformAbilityRuntime.resetRecordDeletionGuard();
        PlatformAbilityRuntime.resetMutationTransactionOperator();
        TenantContext.clear(); CurrentUserContext.clear(); CacheRegistry.clearAll();
    }

    @ParameterizedTest
    @CsvSource({"false,false,processing", "false,true,processing", "true,false,processing", "true,true,processing",
            "false,false,approved", "false,true,approved", "true,false,approved", "true,true,approved"})
    void runningFlowBlocksBothDeleteModesRegardlessOfApprovalMilestone(boolean dynamic, boolean hard, String status) {
        var fixture = fixture(dynamic, status);
        assertThatThrownBy(() -> delete(fixture, hard)).hasMessageContaining("运行中的流程");
        assertThat(active(fixture)).isTrue();
        assertThat(instances.findById(fixture.instanceId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(tasks.findById(fixture.taskId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        actions.forceTerminate(WorkflowInstanceActionRequest.terminate(fixture.instanceId(), "manager", "停止后删除"));
        assertThat(delete(fixture, hard)).isEqualTo(1);
        assertThat(active(fixture)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void missingBusinessCanBeForceClosedAndArchivedWithoutRestoration(boolean dynamic, boolean hard) {
        var fixture = fixture(dynamic, "processing");
        corruptDelete(fixture, hard);
        assertThatThrownBy(() -> actions.terminate(WorkflowInstanceActionRequest.terminate(fixture.instanceId(), "manager", "正常终止")))
                .hasMessageContaining(dynamic ? "record not found" : "record data permission denied");
        assertThat(instances.findById(fixture.instanceId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        var result = actions.forceTerminate(WorkflowInstanceActionRequest.terminate(fixture.instanceId(), "manager", "业务记录已丢失，关闭待办"));
        assertThat(result.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.TERMINATED);
        assertThat(tasks.findById(fixture.taskId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.INVALIDATED);
        actions.managementReset(WorkflowInstanceActionRequest.terminate(fixture.instanceId(), "manager", "归档失联实例"));
        assertThat(instances.findById(fixture.instanceId())).isNull();
        var retained = histories.query(Criteria.of().eq("id", fixture.instanceId()), PageRequest.of(1, 10));
        assertThat(retained).hasSize(1);
        var snapshot = archive.parseSnapshot(retained.getFirst());
        assertThat(snapshot.tasks()).hasSize(1).allMatch(task -> task.getTaskStatus() == WorkflowTaskStatus.INVALIDATED);
        assertThat(snapshot.events()).anyMatch(event -> "forceTerminate".equals(event.getActionCode()))
                .anyMatch(event -> "reset".equals(event.getActionCode()));
        assertThat(active(fixture)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void managementResetCanArchiveRunningOrphanAndCancelItsTodos(boolean dynamic, boolean hard) {
        var fixture = fixture(dynamic, "processing"); corruptDelete(fixture, hard);
        actions.managementReset(WorkflowInstanceActionRequest.terminate(fixture.instanceId(), "manager", "清理失联待办"));
        assertThat(instances.findById(fixture.instanceId())).isNull();
        assertThat(tasks.findById(fixture.taskId())).isNull();
        var retained = histories.query(Criteria.of().eq("id", fixture.instanceId()), PageRequest.of(1, 10));
        assertThat(retained).hasSize(1);
        assertThat(archive.parseSnapshot(retained.getFirst()).tasks()).hasSize(1)
                .allMatch(task -> task.getTaskStatus() == WorkflowTaskStatus.CANCELED);
        assertThat(active(fixture)).isFalse();
    }

    @Test void recoveryDoesNotHideExistingRecordWriteFailuresOrCommitHalfAnArchive() {
        for (boolean reset : List.of(false, true)) {
            var fixture = fixture(false, "processing"); business.rejectSummary = true;
            assertThatThrownBy(() -> {
                var request = WorkflowInstanceActionRequest.terminate(fixture.instanceId(), "manager", "验证正常写入失败回滚");
                if (reset) actions.managementReset(request); else actions.forceTerminate(request);
            }).hasMessageContaining("summary write rejected");
            business.rejectSummary = false;
            assertThat(instances.findById(fixture.instanceId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
            assertThat(tasks.findById(fixture.taskId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(histories.count(Criteria.of().eq("id", fixture.instanceId()))).isZero();
            assertThat(business.select(fixture.recordId()).getApprovalStatus()).isEqualTo("processing");
        }
    }

    @Test void dynamicSecondaryEntityDoesNotInheritTheMainRecordsWorkflowBinding() {
        var fixture = fixture(true, "processing");
        var main = records.select(fixture.module(), "entry", fixture.recordId()).getEntity();
        var secondary = new EntityDefinition("line", fixture.table() + "_line", "Secondary", List.of());
        schema.ensureTable(secondary);
        runtime.refresh(ModuleDefinition.builder(fixture.module(), "Multiple entities")
                .entities(List.of(main, secondary)).mainEntityAlias("entry").build());
        var line = records.newRecord(fixture.module(), "line"); line.setId(fixture.recordId());
        records.create(fixture.module(), "line", line);
        assertThat(records.delete(fixture.module(), "line", line.getId(), line.getVersion())).isEqualTo(1);
        assertThat(active(fixture)).isTrue();
        assertThat(instances.findById(fixture.instanceId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test void nonApprovalRunningWorkflowAlsoProtectsItsBusinessRecord() {
        var fixture = fixture(false, "processing");
        var instance = instances.findById(fixture.instanceId()); instance.setApprovalEnabled(false);
        instances.updateById(instance);
        assertThatThrownBy(() -> delete(fixture, false)).hasMessageContaining("运行中的流程");
        assertThat(active(fixture)).isTrue();
    }

    @Test void crossTenantPermissionCannotBindAnotherTenantsRecordToTheCurrentWorkflowPartition() {
        String id = business.insert(new BusinessRecord());
        String alias = createNonApprovalDefinition();
        try (var otherTenant = TenantContext.use("other-workflow-tenant")) {
            assertThat(business.selectForAction(PlatformAction.VIEW, id)).isNotNull(); // The read grant is intentionally cross tenant.
            assertThatThrownBy(() -> submitter.submitWorkflow(StaticBusiness.MODULE, id, alias))
                    .hasMessageContaining("workflow record tenant does not match");
        }
        assertThat(instances.count(Criteria.of().eq("moduleAlias", StaticBusiness.MODULE).eq("recordId", id))).isZero();
        var submitted = submitter.submitWorkflow(StaticBusiness.MODULE, id, alias);
        assertThat(submitted.instance().getTenantId()).isEqualTo(tenant);
        assertThat(submitted.instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThatThrownBy(() -> business.delete(id, business.selectActiveRaw(id).getVersion()))
                .hasMessageContaining("运行中的流程");
    }

    @Test void realSubmissionAndDeletionSerializeUntilTheSubmitTransactionCommits() throws Exception {
        String id = business.insert(new BusinessRecord());
        String alias = createNonApprovalDefinition();
        pauses.submitRecord = id;
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                Thread.currentThread().setName("first-submit");
                try (var scope = TenantContext.use(tenant)) { return submitter.submitWorkflow(StaticBusiness.MODULE, id, alias); }
            });
            assertThat(pauses.reached.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> {
                try (var scope = TenantContext.use(tenant)) { return business.delete(id, 1); }
            });
            try { assertThatThrownBy(() -> deletion.get(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class); }
            finally { pauses.release.countDown(); }
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
            assertThatThrownBy(() -> deletion.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .hasStackTraceContaining("运行中的流程");
        }
        assertThat(business.selectActiveRaw(id)).isNotNull();
    }

    private String createNonApprovalDefinition() {
        String alias = "nonapproval_" + UUID.randomUUID().toString().replace("-", "");
        var definition = new WorkflowDefinition(); definition.setApplicationAlias("test"); definition.setModuleAlias(StaticBusiness.MODULE);
        definition.setAlias(alias); definition.setTitle("Deletion lifecycle"); definition.setApprovalEnabled(false);
        definition.setEnabled(true); definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED); definition.setCurrentVersionNo(1);
        EntityLifecycle.prepareInsert(definition, Instant.now()); definitions.insert(definition);
        var version = new WorkflowVersion(); version.setDefinitionId(definition.getId()); version.setVersionNo(1);
        version.setPublishStatus(WorkflowPublishStatus.PUBLISHED); version.setSnapshotText("{}");
        EntityLifecycle.prepareInsert(version, Instant.now()); versions.insert(version);
        for (String key : List.of("start", "approve", "end")) {
            var node = new WorkflowNodeDefinition(); node.setWorkflowVersionId(version.getId()); node.setNodeKey(key); node.setTitle(key);
            node.setNodeType(key.equals("start") ? WorkflowNodeType.START : key.equals("end") ? WorkflowNodeType.END : WorkflowNodeType.APPROVAL);
            if (key.equals("approve")) node.setParticipantPolicyText("{\"rules\":[{\"type\":\"USER\",\"ids\":[\"manager\"]}]}");
            EntityLifecycle.prepareInsert(node, Instant.now()); nodeDefinitions.insert(node);
        }
        for (String key : List.of("start", "approve")) {
            var link = new WorkflowLinkDefinition(); link.setWorkflowVersionId(version.getId()); link.setRouteKey(key + "-next"); link.setTitle(key);
            link.setSourceNodeKey(key); link.setTargetNodeKey(key.equals("start") ? "approve" : "end");
            EntityLifecycle.prepareInsert(link, Instant.now()); links.insert(link);
        }
        return alias;
    }

    private Fixture fixture(boolean dynamic, String approvalStatus) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String module = dynamic ? "test.deletion_" + suffix : StaticBusiness.MODULE;
        String table = dynamic ? "workflow_deletion_" + suffix : "test_workflow_deletion_business";
        String recordId;
        if (dynamic) {
            var entity = new EntityDefinition("entry", table, "Deletion business", List.of()).withCapabilities(EntityCapability.APPROVAL, EntityCapability.RECYCLE_BIN);
            schema.ensureTable(entity); runtime.register(ModuleDefinition.builder(module, "Deletion business").entities(List.of(entity)).build());
            recordId = records.create(module, "entry", records.newRecord(module, "entry"));
        } else recordId = business.insert(new BusinessRecord());
        var instance = new WorkflowInstance(); instance.setModuleAlias(module); instance.setRecordId(recordId);
        instance.setDefinitionId("definition"); instance.setWorkflowVersionId("version"); instance.setVersionNo(1);
        instance.setApprovalEnabled(true); instance.setApprovalStatus("approved".equals(approvalStatus) ? WorkflowApprovalStatus.APPROVED : WorkflowApprovalStatus.PROCESSING);
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING); instance.setStartedBy("manager");
        instance.setStartedAt(Instant.now()); instance.setSnapshotText("{}");
        EntityLifecycle.prepareInsert(instance, Instant.now()); instances.insert(instance);
        var task = new WorkflowTask(); task.setInstanceId(instance.getId()); task.setTaskKind(WorkflowTaskKind.APPROVAL);
        task.setTaskStatus(WorkflowTaskStatus.TODO); task.setAssigneeId("manager");
        EntityLifecycle.prepareInsert(task, Instant.now()); tasks.insert(task);
        summaries.writeSubmitted(new WorkflowApprovalSummary(tenant, module, recordId, instance.getId(), instance.getApprovalStatus(), "manager", instance.getStartedAt(), null));
        return new Fixture(module, recordId, table, instance.getId(), task.getId(), dynamic);
    }
    private int delete(Fixture fixture, boolean hard) {
        if (fixture.dynamic()) {
            var service = runtime.entityService(fixture.module(), "entry");
            var record = service.selectActiveRaw(fixture.recordId());
            int deleted = records.delete(fixture.module(), "entry", fixture.recordId(), record.getVersion());
            if (!hard) return deleted;
            return service.purge(fixture.recordId(), service.selectIgnoreSoftDelete(fixture.recordId()).getVersion());
        }
        CrudAbility<BusinessRecord> service = hard
                ? new AbstractAbilityService<>(fixture.module(), BusinessRecord.class, businessDao) {} : business;
        return service.delete(fixture.recordId(), service.selectActiveRaw(fixture.recordId()).getVersion());
    }
    private boolean active(Fixture fixture) {
        return fixture.dynamic() ? records.existsActiveInCurrentTenant(fixture.module(), "entry", fixture.recordId())
                : business.selectActiveRaw(fixture.recordId()) != null;
    }
    private void corruptDelete(Fixture fixture, boolean hard) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update((hard ? "delete from " : "update ") + fixture.table()
                + (hard ? " where id = ?" : " set deleted = true where id = ?"), fixture.recordId());
    }
    record Fixture(String module, String recordId, String table, String instanceId, String taskId, boolean dynamic) {}
    @Getter @Setter @Table(name = "test_workflow_deletion_business")
    public static class BusinessRecord extends StandardApprovalEntity {}
    @MuYunRepository public interface StaticDao extends BaseDao<BusinessRecord, String> {}
    static class StaticBusiness extends AbstractAbilityService<BusinessRecord> implements ApprovalAbility<BusinessRecord>, SoftDeleteAbility<BusinessRecord>, DataScopeAbility<BusinessRecord> {
        static final String MODULE = "test.deletion_static";
        boolean rejectSummary;
        StaticBusiness(StaticDao dao) { super(MODULE, BusinessRecord.class, dao); }
        @Override public void beforeUpdate(BusinessRecord record) {
            if (rejectSummary) throw new IllegalStateException("summary write rejected");
        }
    }
    @SpringBootConfiguration
    @Import({WorkflowConcurrencyRepositoryIT.Host.class, WorkflowInstanceActionService.class, WorkflowRecordDeletionGuard.class,
            StaticWorkflowModuleRecordGuard.class, DynamicWorkflowModuleRecordGuard.class, WorkflowModuleSubmitService.class})
    static class Host {
        @Bean StaticBusiness business(StaticDao dao) { return new StaticBusiness(dao); }
        @Bean DynamicRecordRuntime runtime(IDatabaseOperations<?> operations) { return DynamicRecordRuntime.builder(operations).build(); }
        @Bean DynamicRecordService records(DynamicRecordRuntime runtime) { return new DynamicRecordService(runtime); }
        @Bean DynamicSchemaService schema(IDatabaseOperations<?> operations) { return new DynamicSchemaService(operations); }
        @Bean @Primary WorkflowApprovalSummaryWriter realSummaries(DynamicRecordService records,
                org.springframework.beans.factory.ObjectProvider<CrudAbility<?>> abilities) {
            return new DynamicWorkflowApprovalSummaryWriter(records, abilities);
        }
    }
}
