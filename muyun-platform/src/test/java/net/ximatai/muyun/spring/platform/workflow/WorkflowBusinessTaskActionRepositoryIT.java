package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.platform.task.ModuleCompletionCheckService;
import net.ximatai.muyun.spring.platform.ui.*;
import org.junit.jupiter.api.*;
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
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = WorkflowBusinessTaskActionRepositoryIT.Host.class)
class WorkflowBusinessTaskActionRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired WorkflowBusinessTaskActionService businessActions;
    @Autowired WorkflowTaskActionService taskActions;
    @Autowired WorkflowConditionService conditions;
    @Autowired WorkflowSubmitFacade submit;
    @Autowired WorkflowBusinessTaskResolver specifications;
    @Autowired WorkflowDefinitionDao definitions;
    @Autowired WorkflowVersionDao versions;
    @Autowired WorkflowNodeDefinitionDao nodes;
    @Autowired WorkflowLinkDefinitionDao links;
    @Autowired WorkflowTaskDefinitionDao taskDefinitions;
    @Autowired WorkflowTaskCheckDao checks;
    @Autowired WorkflowTaskGuideDao guides;
    @Autowired WorkflowTaskCheckResultDao checkResults;
    @Autowired WorkflowTaskDao tasks;
    @Autowired WorkflowInstanceDao instances;
    @Autowired WorkflowRouteInstanceDao routeRuns;
    @Autowired WorkflowNodeInstanceDao nodeRuns;
    @Autowired StaticBusiness business;
    @Autowired DynamicRecordRuntime dynamicRuntime;
    @Autowired DynamicRecordService dynamicRecords;
    @Autowired DynamicSchemaService schema;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired javax.sql.DataSource dataSource;
    private final String tenant = "atomic-task-tenant";

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
    }
    @AfterEach void resetHost() { PlatformAbilityRuntime.resetMutationTransactionOperator(); TenantContext.clear(); CurrentUserContext.clear(); }

    @Test void staticStandardUpdateAndLiveCheckMustCommitOrRollbackAsOneTransaction() { checkAtomicUpdate(false, "edit"); }
    @Test void dynamicStandardUpdateAndLiveCheckMustCommitOrRollbackAsOneTransaction() { checkAtomicUpdate(true, "save-action"); }

    @Test void customDynamicActionKeepsApprovalProtectionAndRollsBackWithCompletionChecks() {
        try (var tenantContext = TenantContext.use(tenant);
             var actor = CurrentUserContext.use(CurrentUser.tenantUser("operator", "Operator", tenant))) {
            var fixture = fixture(true, false, true);
            assertThatThrownBy(() -> dynamicRecords.executeAction(fixture.module(), "finishBusiness",
                    DynamicActionExecutionRequest.id(fixture.recordId()).withPayload(Map.of("ready", true))))
                    .hasMessageContaining("不可直接修改");
            assertThatThrownBy(() -> businessActions.execute(fixture.taskId(), "custom-failed", fixture.recordVersion(),
                    Map.of(), Map.of(), "operator", "complete"))
                    .hasMessageContaining("业务准备尚未完成");
            assertThat(facts(fixture)).containsEntry("title", "initial").containsEntry("manualConfirm", false)
                    .containsEntry("version", fixture.recordVersion());
            assertThat(tasks.findById(fixture.taskId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(checkResults.query(Criteria.of().eq("taskId", fixture.taskId()), PageRequest.of(1, 10))).isEmpty();

            var completed = businessActions.execute(fixture.taskId(), "custom-ready", fixture.recordVersion(),
                    Map.of(), Map.of(), "operator", "complete");
            assertThat(completed.actionResult().task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
            assertThat(completed.actionResult().instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThat(facts(fixture)).containsEntry("title", "custom action").containsEntry("manualConfirm", true)
                    .containsEntry("version", fixture.recordVersion() + 1).containsEntry("updatedBy", "operator");
            assertThat(dynamicRecords.select(fixture.module(), "entry", fixture.recordId()).getApprovalStatus()).isEqualTo("processing");
            assertThat(instances.findById(fixture.instanceId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThatThrownBy(() -> dynamicRecords.executeAction(fixture.module(), "finishBusiness",
                    DynamicActionExecutionRequest.id(fixture.recordId()).withPayload(Map.of("ready", true))))
                    .hasMessageContaining("不可直接修改");
        }
    }

    private void checkAtomicUpdate(boolean dynamic, String guide) {
        try (var tenantContext = TenantContext.use(tenant);
             var actor = CurrentUserContext.use(CurrentUser.tenantUser("operator", "Operator", tenant))) {
            var fixture = fixture(dynamic);
            var before = facts(fixture);
            var failedMutation = new net.ximatai.muyun.spring.ability.action.MutationContext();
            try (var mutationScope = net.ximatai.muyun.spring.ability.action.MutationContextHolder.use(failedMutation)) {
                assertThatThrownBy(() -> businessActions.execute(fixture.taskId(), guide, fixture.recordVersion(),
                        Map.of("title", "must rollback", "manualConfirm", false), Map.of(), "operator", "finish"))
                        .isInstanceOf(PlatformException.class).hasMessageContaining("业务准备尚未完成");
            }
            assertThat(failedMutation.committedChangeSet(type -> fixture.module()).changes()).isEmpty();
            assertThat(facts(fixture)).containsEntry("title", before.get("title")).containsEntry("manualConfirm", false)
                    .containsEntry("version", fixture.recordVersion());
            assertThat(tasks.findById(fixture.taskId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(instances.findById(fixture.instanceId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
            assertThat(checkResults.query(Criteria.of().eq("taskId", fixture.taskId()), PageRequest.of(1, 10))).isEmpty();

            var successfulMutation = new net.ximatai.muyun.spring.ability.action.MutationContext();
            WorkflowBusinessTaskActionService.Result completed;
            try (var mutationScope = net.ximatai.muyun.spring.ability.action.MutationContextHolder.use(successfulMutation)) {
                completed = businessActions.execute(fixture.taskId(), guide, fixture.recordVersion(),
                        Map.of("title", "saved with workflow", "manualConfirm", true), Map.of(), "operator", "finish");
            }
            assertThat(successfulMutation.committedChangeSet(type -> fixture.module()).changes())
                    .contains(net.ximatai.muyun.spring.ability.action.DataChange.recordUpdated(fixture.module(), fixture.recordId()));
            assertThat(completed.actionResult().task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
            assertThat(completed.actionResult().instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThat(facts(fixture)).containsEntry("title", "saved with workflow").containsEntry("manualConfirm", true)
                    .containsEntry("version", fixture.recordVersion() + 1).containsEntry("updatedBy", "operator");
            assertThat(instances.findById(fixture.instanceId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThat(checkResults.query(Criteria.of().eq("taskId", fixture.taskId()), PageRequest.of(1, 10)))
                    .hasSize(1).allMatch(result -> Boolean.TRUE.equals(result.getPassed()));
        }
    }

    @Test void frozenGuideScopeAndTaskAssigneeAreRequiredBeforeAnyBusinessWrite() throws Exception {
        try (var tenantContext = TenantContext.use(tenant)) {
            var fixture = fixture(false);
            // Historical snapshots still enforce runtime scope even though new publications reject this guide.
            var frozenNode = nodeRuns.findById(tasks.findById(fixture.taskId()).getNodeInstanceId());
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var snapshot = mapper.readTree(frozenNode.getNodeSnapshotText());
            var foreign = new WorkflowTaskGuide(); foreign.setGuideKey("cross-module"); foreign.setTitle("Legacy foreign form");
            foreign.setGuideKind(WorkflowTaskGuideKind.OPEN_FORM); foreign.setTargetModuleAlias("test.unrelated");
            foreign.setEnabled(true); foreign.setGuideConfigText("{\"editableFields\":[\"title\",\"manualConfirm\"]}");
            ((com.fasterxml.jackson.databind.node.ArrayNode) snapshot.path("task").path("guides")).add(mapper.valueToTree(foreign));
            frozenNode.setNodeSnapshotText(mapper.writeValueAsString(snapshot)); nodeRuns.updateById(frozenNode);
            Map<String, String> rejected = Map.of("not-declared", "指引不存在", "instructions", "不支持业务写入", "cross-module", "必须绑定当前任务业务");
            for (var entry : rejected.entrySet()) {
                assertThatThrownBy(() -> businessActions.execute(fixture.taskId(), entry.getKey(), fixture.recordVersion(),
                        Map.of("title", "forged", "manualConfirm", true), Map.of(), "operator", "finish"))
                        .isInstanceOf(PlatformException.class).hasMessageContaining(entry.getValue());
            }
            assertThatThrownBy(() -> businessActions.execute(fixture.taskId(), "edit", fixture.recordVersion(),
                    Map.of("manualConfirm", true), Map.of(), "stranger", "finish"))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("not assignee");
            assertThatThrownBy(() -> businessActions.execute(fixture.taskId(), "edit", fixture.recordVersion(),
                    Map.of("moduleAlias", "forged"), Map.of(), "operator", "finish"))
                    .hasMessageContaining("未授权的字段");
            assertThat(facts(fixture)).containsEntry("title", "initial").containsEntry("version", fixture.recordVersion());
            assertThat(tasks.findById(fixture.taskId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        }
    }

    @Test void businessSaveAndManualSelectionCommitOrRollbackTogether() {
        try (var tenantContext = TenantContext.use(tenant);
             var actor = CurrentUserContext.use(CurrentUser.tenantUser("operator", "Operator", tenant))) {
            var fixture = fixture(false, true);
            assertThat(nodeRuns.query(Criteria.of().eq("instanceId", fixture.instanceId()).eq("nodeKey", "business"),
                    PageRequest.of(1, 1)).getFirst().getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
            var frozenRoutes = routeRuns.query(Criteria.of().eq("instanceId", fixture.instanceId()), PageRequest.of(1, 20), Sort.asc("routeKey"));

            // No choice, a route belonging to another branch, or a missing required reason all roll back the form save.
            for (var selections : List.of(List.<WorkflowManualRouteSelection>of(),
                    List.of(new WorkflowManualRouteSelection("choice", "review-business", "wrong branch")),
                    List.of(new WorkflowManualRouteSelection("choice", "choice-selected", null)))) {
                assertThatThrownBy(() -> businessActions.execute(fixture.taskId(), "edit", fixture.recordVersion(),
                        Map.of("title", "must rollback", "manualConfirm", true), Map.of(), "operator", "finish", selections))
                        .isInstanceOf(PlatformException.class);
                assertThat(facts(fixture)).containsEntry("title", "initial").containsEntry("manualConfirm", false)
                        .containsEntry("version", fixture.recordVersion());
                assertThat(tasks.findById(fixture.taskId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
                assertThat(checkResults.query(Criteria.of().eq("taskId", fixture.taskId()), PageRequest.of(1, 10))).isEmpty();
                assertThat(routeRuns.query(Criteria.of().eq("instanceId", fixture.instanceId()), PageRequest.of(1, 20), Sort.asc("routeKey")))
                        .usingRecursiveComparison().isEqualTo(frozenRoutes);
            }

            var completed = businessActions.execute(fixture.taskId(), "edit", fixture.recordVersion(),
                    Map.of("title", "saved and selected", "manualConfirm", true), Map.of(), "operator", "finish",
                    List.of(new WorkflowManualRouteSelection("choice", "choice-selected", "choose selected route")));
            assertThat(completed.actionResult().task().getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
            assertThat(completed.actionResult().instance().getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThat(facts(fixture)).containsEntry("title", "saved and selected").containsEntry("manualConfirm", true)
                    .containsEntry("version", fixture.recordVersion() + 1).containsEntry("updatedBy", "operator");
            assertThat(instances.findById(fixture.instanceId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            var chosen = routeRuns.query(Criteria.of().eq("instanceId", fixture.instanceId()).eq("routeKey", "choice-selected"),
                    PageRequest.of(1, 1)).getFirst();
            assertThat(chosen.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CLOSED);
            assertThat(chosen.getSelectedBy()).isEqualTo("operator");
            assertThat(chosen.getSelectedReason()).isEqualTo("choose selected route");
            assertThat(routeRuns.query(Criteria.of().eq("instanceId", fixture.instanceId()).eq("routeKey", "choice-alternate"),
                    PageRequest.of(1, 1)).getFirst().getRouteStatus()).isEqualTo(WorkflowRouteStatus.INEFFECTIVE);
            assertThat(checkResults.query(Criteria.of().eq("taskId", fixture.taskId()), PageRequest.of(1, 10)))
                    .hasSize(1).allMatch(result -> Boolean.TRUE.equals(result.getPassed()));
        }
    }

    private Fixture fixture(boolean dynamic) {
        return fixture(dynamic, false);
    }

    private Fixture fixture(boolean dynamic, boolean manualContinuation) {
        return fixture(dynamic, manualContinuation, false);
    }

    private Fixture fixture(boolean dynamic, boolean manualContinuation, boolean customAction) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String module = dynamic ? "test.atomic_" + suffix : StaticBusiness.MODULE;
        String recordId;
        if (dynamic) {
            var entity = new EntityDefinition("entry", "atomic_business_" + suffix, "Atomic business", List.of(
                    FieldDefinition.string("title", "Title"), FieldDefinition.of("manualConfirm", FieldType.BOOLEAN, "Ready").column("manual_confirm")));
            if (customAction) entity = entity.withCapabilities(EntityCapability.APPROVAL);
            schema.ensureTable(entity);
            var actionDefinitions = new ArrayList<EntityActionDefinition>();
            if (customAction) {
                var executor = new DynamicActionExecutor() {
                    @Override public String executorKey() { return "finish_business_" + suffix; }
                    @Override public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
                        throw new UnsupportedOperationException("business action requires standard operations");
                    }
                    @Override public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request,
                                                    DynamicActionOperations operations) {
                        var record = operations.select(request.recordId());
                        record.setValue("title", "custom action").setValue("manualConfirm", request.payload().get("ready"));
                        record.setApprovalStatus("forged");
                        return operations.update(record);
                    }
                };
                dynamicRuntime.actionExecutorRegistry().register(executor);
                actionDefinitions.add(new EntityActionDefinition("entry", "finishBusiness", "Finish", true,
                        EntityActionLevel.RECORD, EntityActionCategory.CUSTOM, null, true, false, null, null, null,
                        EntityActionExecutorType.SERVICE, executor.executorKey()));
            }
            dynamicRuntime.register(ModuleDefinition.builder(module, "Atomic business").entities(List.of(entity))
                    .actions(actionDefinitions).build());
            recordId = dynamicRecords.create(module, "entry", dynamicRecords.newRecord(module, "entry")
                    .setValue("title", "initial").setValue("manualConfirm", false));
            if (customAction) dynamicRecords.writeApprovalState(module, "entry", recordId, PlatformAction.UPDATE.executionPolicy(),
                    new ApprovalState("approval-instance", "processing", "operator", Instant.now(), null));
        } else {
            var record = new WorkflowTaskDefinition(); record.setModuleAlias(module); record.setAlias("business_" + suffix);
            record.setTitle("initial"); record.setManualConfirm(false); recordId = business.insert(record);
        }
        var definition = new WorkflowDefinition(); definition.setApplicationAlias("test"); definition.setModuleAlias(module);
        definition.setAlias("run_" + suffix); definition.setTitle("Atomic workflow"); definition.setEnabled(true);
        definition.setApprovalEnabled(false); definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED); definition.setCurrentVersionNo(1);
        insert(definition, definitions::insert);
        var version = new WorkflowVersion(); version.setDefinitionId(definition.getId()); version.setVersionNo(1);
        version.setPublishStatus(WorkflowPublishStatus.PUBLISHED); version.setSnapshotText("{}"); insert(version, versions::insert);
        var taskDefinition = new WorkflowTaskDefinition(); taskDefinition.setModuleAlias(module); taskDefinition.setAlias("ready_" + suffix);
        taskDefinition.setTitle("Completion policy"); taskDefinition.setEnabled(true); taskDefinition.setManualConfirm(false); insert(taskDefinition, taskDefinitions::insert);
        var check = new WorkflowTaskCheck(); check.setTaskDefinitionId(taskDefinition.getId()); check.setTitle("Ready"); check.setCheckKey("ready");
        check.setEnabled(true); check.setCheckKind(WorkflowTaskCheckKind.FORMULA); check.setExpression("{manualConfirm} == true");
        check.setFailureMessage("业务准备尚未完成"); insert(check, checks::insert);
        guide(taskDefinition.getId(), "edit", WorkflowTaskGuideKind.OPEN_FORM, module, null);
        guide(taskDefinition.getId(), "save-action", WorkflowTaskGuideKind.EXECUTE_ACTION, module, "update");
        guide(taskDefinition.getId(), "instructions", WorkflowTaskGuideKind.READ_INSTRUCTION, null, null);
        if (customAction) {
            for (var ready : List.of(false, true)) {
                var guide = new WorkflowTaskGuide(); guide.setTaskDefinitionId(taskDefinition.getId());
                guide.setGuideKey(ready ? "custom-ready" : "custom-failed"); guide.setTitle("Custom action");
                guide.setEnabled(true); guide.setGuideKind(WorkflowTaskGuideKind.EXECUTE_ACTION);
                guide.setTargetModuleAlias(module); guide.setTargetActionCode("finishBusiness");
                guide.setGuideConfigText("{\"payload\":{\"ready\":" + ready + "}}"); insert(guide, guides::insert);
            }
        }
        var authoredNodes = new ArrayList<WorkflowNodeDefinition>();
        var authoredLinks = new ArrayList<WorkflowLinkDefinition>();
        var nodeKeys = manualContinuation ? List.of("start", "review", "business", "choice", "selected", "alternate", "merge", "end")
                : List.of("start", "business", "end");
        for (String key : nodeKeys) {
            var node = new WorkflowNodeDefinition(); node.setWorkflowVersionId(version.getId()); node.setNodeKey(key); node.setTitle(key);
            node.setNodeType(switch (key) {
                case "start" -> WorkflowNodeType.START;
                case "end" -> WorkflowNodeType.END;
                case "business" -> WorkflowNodeType.TASK;
                case "review" -> WorkflowNodeType.APPROVAL;
                case "choice" -> WorkflowNodeType.BRANCH;
                case "merge" -> WorkflowNodeType.CONVERGE;
                default -> WorkflowNodeType.MILESTONE;
            });
            if (key.equals("business")) { node.setTaskDefinitionId(taskDefinition.getId()); node.setParticipantPolicyText("user:operator"); specifications.freeze(node, module); }
            if (key.equals("review")) { node.setParticipantPolicyText("user:operator"); node.setApprovalMode(WorkflowApprovalMode.ALL); }
            if (key.equals("choice")) { node.setRouteMode(WorkflowRouteMode.MANUAL); node.setConvergeNodeKey("merge");
                node.setSelectorNodeKey("business"); node.setRequireManualSelectionReason(true); }
            if (node.getNodeType() == WorkflowNodeType.CONVERGE) node.setConvergeMode(WorkflowConvergeMode.ALL);
            insert(node, nodes::insert);
            authoredNodes.add(node);
        }
        var edgePairs = manualContinuation ? List.of(List.of("start", "review"), List.of("review", "business"),
                List.of("business", "choice"), List.of("choice", "selected"), List.of("choice", "alternate"),
                List.of("selected", "merge"), List.of("alternate", "merge"), List.of("merge", "end"))
                : List.of(List.of("start", "business"), List.of("business", "end"));
        for (var pair : edgePairs) {
            var edge = new WorkflowLinkDefinition(); edge.setWorkflowVersionId(version.getId()); edge.setTitle(pair.getFirst());
            edge.setRouteKey(manualContinuation ? pair.getFirst() + "-" + pair.getLast() : pair.getFirst() + "-next");
            edge.setSourceNodeKey(pair.getFirst()); edge.setTargetNodeKey(pair.getLast()); insert(edge, links::insert);
            authoredLinks.add(edge);
        }
        // Publishing binds the manual choice to the task that directly triggers this continuation.
        new WorkflowDesignCompiler(conditions).validate(new WorkflowDesignDocument(authoredNodes, authoredLinks, null), true, false);
        var submitted = submit.submit(WorkflowSubmitRequest.workflow(module, recordId, definition.getAlias()).withOperator("operator"));
        int recordVersion = dynamic ? dynamicRecords.select(module, "entry", recordId).getVersion() : business.select(recordId).getVersion();
        if (manualContinuation) {
            var review = submitted.draft().tasks().getFirst();
            taskActions.approve(WorkflowTaskActionRequest.builder(review.getId(), "operator").build());
        }
        String taskId = tasks.query(Criteria.of().eq("instanceId", submitted.instance().getId())
                .eq("taskKind", WorkflowTaskKind.BUSINESS).eq("taskStatus", WorkflowTaskStatus.TODO), PageRequest.of(1, 10))
                .getFirst().getId();
        return new Fixture(module, recordId, recordVersion, taskId, submitted.instance().getId(), dynamic);
    }
    private void guide(String definition, String key, WorkflowTaskGuideKind kind, String module, String action) {
        var guide = new WorkflowTaskGuide(); guide.setTaskDefinitionId(definition); guide.setGuideKey(key); guide.setTitle(key);
        guide.setGuideConfigText("{\"editableFields\":[\"title\",\"manualConfirm\"]}");
        guide.setEnabled(true); guide.setGuideKind(kind); guide.setTargetModuleAlias(module); guide.setTargetActionCode(action); insert(guide, guides::insert);
    }
    private <T extends net.ximatai.muyun.spring.common.model.contract.EntityContract> void insert(T record, java.util.function.Function<T, String> writer) {
        EntityLifecycle.prepareInsert(record, Instant.now()); writer.apply(record);
    }
    private Map<String, Object> facts(Fixture fixture) {
        if (fixture.dynamic()) {
            var record = dynamicRecords.select(fixture.module(), "entry", fixture.recordId());
            var result = new HashMap<>(record.getValues()); result.put("version", record.getVersion()); result.put("updatedBy", record.getUpdatedBy()); return result;
        }
        var record = business.select(fixture.recordId());
        var result = new HashMap<String, Object>(); result.put("title", record.getTitle()); result.put("manualConfirm", record.getManualConfirm());
        result.put("version", record.getVersion()); result.put("updatedBy", record.getUpdatedBy()); return result;
    }
    record Fixture(String module, String recordId, int recordVersion, String taskId, String instanceId, boolean dynamic) {}
    static class StaticBusiness extends AbstractAbilityService<WorkflowTaskDefinition> {
        static final String MODULE = "test.atomic_static";
        StaticBusiness(WorkflowTaskDefinitionDao dao) { super(MODULE, WorkflowTaskDefinition.class, dao); }
    }

    @SpringBootConfiguration
    @Import({WorkflowRepositoryTestConfiguration.class, WorkflowModuleTaskRuntimeService.class,
            WorkflowBusinessTaskActionService.class, DefaultModuleRecordActionExecutor.class, ModuleCompletionCheckService.class})
    static class Host {
        @Bean StaticBusiness business(WorkflowTaskDefinitionDao dao) { return new StaticBusiness(dao); }
        @Bean DynamicRecordRuntime dynamicRuntime(IDatabaseOperations<?> operations) { return DynamicRecordRuntime.builder(operations).build(); }
        @Bean DynamicRecordService dynamicRecords(DynamicRecordRuntime runtime) { return new DynamicRecordService(runtime); }
        @Bean DynamicSchemaService schema(IDatabaseOperations<?> operations) { return new DynamicSchemaService(operations); }
        @Bean ActionExecutionPolicyService policies() { return new AllowAllActionExecutionPolicyService(); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean PlatformQueryItemService queries() { return mock(PlatformQueryItemService.class); }
        @Bean PlatformQueryTemplateService templates() { return mock(PlatformQueryTemplateService.class); }
        @Bean @Primary ModuleRecordFacts realFacts(org.springframework.beans.factory.ObjectProvider<CrudAbility<?>> abilities, DynamicRecordService records) {
            return new DefaultModuleRecordFacts(abilities, records);
        }
        @Bean @Primary WorkflowBusinessTaskResolver realSpecifications(WorkflowTaskDefinitionDao definitions, WorkflowTaskCheckDao checks,
                WorkflowTaskGuideDao guides, WorkflowConditionService conditions) { return new WorkflowBusinessTaskResolver(definitions, checks, guides, conditions, mock(WorkflowBusinessTaskReferenceValidator.class)); }
        @Bean @Primary WorkflowModuleTaskEvaluator realEvaluator(WorkflowBusinessTaskResolver specifications, ModuleCompletionCheckService checks) {
            return new DefaultWorkflowModuleTaskEvaluator(specifications, checks);
        }
        @Bean WorkflowTaskActionFacade taskActions(WorkflowTaskActionService actions) { return new WorkflowTaskActionFacade(actions, mock(WorkflowTaskActionAvailabilityService.class)); }
    }
}
