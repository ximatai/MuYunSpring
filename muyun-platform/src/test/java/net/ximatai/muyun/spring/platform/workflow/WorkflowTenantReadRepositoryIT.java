package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Exercise repository filtering, including the same user and business record in two tenants. */
@SpringBootTest(classes = WorkflowTenantReadRepositoryIT.Host.class)
class WorkflowTenantReadRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired WorkflowInstanceDao instances;
    @Autowired WorkflowTaskDao tasks;
    @Autowired WorkflowNodeInstanceDao nodes;
    @Autowired WorkflowRouteInstanceDao routes;
    @Autowired WorkflowEventDao events;
    @Autowired WorkflowHistoryInstanceDao histories;
    @Autowired WorkflowDefinitionDao definitions;
    @Autowired WorkflowDefinitionService definitionService;
    @Autowired WorkflowVersionDao versions;
    @Autowired WorkflowDefinitionSelector selector;
    @Autowired WorkflowConditionService conditions;
    @Autowired WorkflowNodeDefinitionDao nodeDefinitions;
    @Autowired WorkflowLinkDefinitionDao linkDefinitions;
    @Autowired WorkflowSubmitFacade submissions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    private final PageRequest page = PageRequest.of(1, 100);
    private String module;
    private Fixture first, second, global;
    private WorkflowRuntimeReadFacade runtime;
    private WorkflowTaskQueryService queries;
    private WorkflowAdminService admin;
    private WorkflowHistoryQueryService history;
    private WorkflowSubmitReadFacade status;

    @BeforeEach void configure() {
        var transactions = new TransactionTemplate(transactionManager);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            @Override public <T> T execute(java.util.function.Supplier<T> work) { return transactions.execute(tx -> work.get()); }
            @Override public void lock(String scope, String key) {
                new JdbcTemplate(dataSource).queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + ":" + key);
            }
        });
        module = "test.tenant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var policy = new WorkflowActionPolicyService();
        runtime = new WorkflowRuntimeReadFacade(instances, tasks, nodes, routes, events,
                mock(WorkflowTaskActionAvailabilityService.class), policy, new WorkflowTaskAssignmentPolicyService(),
                WorkflowTestSupport.provider(WorkflowUserTitleResolver.NONE),
                WorkflowTestSupport.provider(WorkflowRecordSummaryResolver.NONE), conditions);
        queries = new WorkflowTaskQueryService(tasks, events);
        history = new WorkflowHistoryQueryService(histories, mock(WorkflowArchiveService.class), policy,
                WorkflowTestSupport.provider(WorkflowUserTitleResolver.NONE),
                mock(net.ximatai.muyun.spring.platform.content.BusinessContentFacts.class));
        admin = new WorkflowAdminService(instances, tasks, nodes, routes, events, policy,
                mock(WorkflowInstanceActionService.class), mock(WorkflowTaskActionService.class), history, Optional.empty());
        status = new WorkflowSubmitReadFacade(instances, mock(WorkflowDefinitionSelector.class), mock(WorkflowSubmitFacade.class), conditions);
        first = fixture("read-tenant-a"); second = fixture("read-tenant-b"); global = fixture(null);
    }

    @AfterEach void clear() {
        PlatformAbilityRuntime.resetMutationTransactionOperator(); TenantContext.clear(); CurrentUserContext.clear();
    }

    @Test void selectedTenantLimitsUserAndSystemAdministratorQueriesAndRejectsForeignIds() {
        try (var principal = CurrentUserContext.use(CurrentUser.systemUser("reviewer", "admin"));
             var context = TenantContext.use("read-tenant-a")) {
            assertVisible(first);
            assertThat(runtime.renderBundle(first.instance().getId()).nodes()).hasSize(1);
            assertThatThrownBy(() -> runtime.renderBundle(second.instance().getId())).hasMessageContaining("not found");
            assertThatThrownBy(() -> admin.currentTodoTasks(second.instance().getId())).hasMessageContaining("not found");
            assertThatThrownBy(() -> history.renderAdminBundle(second.history().getId())).hasMessageContaining("not found");
            assertThat(queries.instanceTasks(second.instance().getId(), page)).isEmpty();
            assertThat(queries.instanceEvents(second.instance().getId(), page)).isEmpty();
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx -> WorkflowMutationLock.task(tasks, second.task().getId())))
                    .hasMessageContaining("not found");
        }
        try (var context = TenantContext.use("read-tenant-b")) { assertVisible(second); }
    }

    @Test void recordWorkflowDirectoryAppliesTenantAndOwnerBeforeDatabasePaging() {
        Fixture latest;
        try (var context = TenantContext.use("read-tenant-a")) { latest = fixture("read-tenant-a"); }
        var hidden = fixture("read-tenant-a");
        var differentOwner = fixture("read-tenant-a");
        new JdbcTemplate(dataSource).update("update platform_workflow_instance set deleted = true where id = ?", hidden.instance().getId());
        new JdbcTemplate(dataSource).update("update platform_workflow_instance set record_id = 'different-record' where id = ?", differentOwner.instance().getId());
        try (var principal = CurrentUserContext.use(CurrentUser.tenantUser("reviewer", "Reviewer", "read-tenant-a"));
             var context = TenantContext.use("read-tenant-a")) {
            var firstPage = runtime.recordInstances(module, "record", PageRequest.of(1, 1));
            var secondPage = runtime.recordInstances(module, "record", PageRequest.of(2, 1));
            assertThat(firstPage.getTotal()).isEqualTo(2);
            assertThat(firstPage.getRecords()).extracting(WorkflowRecordInstanceView::id).containsExactly(latest.instance().getId());
            assertThat(secondPage.getRecords()).extracting(WorkflowRecordInstanceView::id).containsExactly(first.instance().getId());
            assertThat(runtime.recordInstances(module, "different-record", page).getRecords())
                    .extracting(WorkflowRecordInstanceView::id).containsExactly(differentOwner.instance().getId());
            assertThat(runtime.recordInstances("test.other_module", "record", page).getTotal()).isZero();
            assertThatThrownBy(() -> runtime.recordInstances(module, "record", PageRequest.of(1, 101)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void noTenantAndSystemScopeReadOnlyNullPartitionUnlessBypassIsExplicit() {
        assertVisible(global);
        try (var principal = CurrentUserContext.use(CurrentUser.systemUser("reviewer", "admin"));
             var context = TenantContext.system("global workflow read contract")) { assertVisible(global); }
        try (var context = TenantContext.use("read-tenant-a");
             var bypass = TenantContext.bypassTenantFilter("explicit administrative reconciliation")) {
            assertThat(runtime.todoCards("reviewer", page)).filteredOn(card -> module.equals(card.moduleAlias()))
                    .extracting(WorkflowWorkbenchCard::instanceId).containsExactlyInAnyOrder(first.instance().getId(), second.instance().getId(), global.instance().getId());
            assertThat(history.queryAdminHistory(module, "record", page)).hasSize(3);
        }
    }

    @Test void selectorReadsGlobalAndCallerOwnedConfigurationAndRejectsOtherTenantEvenForSystemAdministrator() {
        var shared = configuration(null, "shared"); var own = configuration("read-tenant-a", "own");
        var foreign = configuration("read-tenant-b", "foreign");
        try (var principal = CurrentUserContext.use(CurrentUser.systemUser("reviewer", "admin"));
             var context = TenantContext.use("read-tenant-a")) {
            assertThat(selector.select(WorkflowSubmitRequest.workflow(module, "record", "shared")).definition().getId()).isEqualTo(shared.getId());
            assertThat(selector.select(WorkflowSubmitRequest.workflow(module, "record", "own")).definition().getId()).isEqualTo(own.getId());
            assertThatThrownBy(() -> selector.select(WorkflowSubmitRequest.workflow(module, "record", "foreign"))).hasMessageContaining("not found");
            assertThat(TenantContext.currentTenantId()).contains("read-tenant-a");
        }
        try (var context = TenantContext.use("read-tenant-b")) {
            assertThat(selector.select(WorkflowSubmitRequest.workflow(module, "record", "foreign")).definition().getId()).isEqualTo(foreign.getId());
            assertThatThrownBy(() -> selector.select(WorkflowSubmitRequest.workflow(module, "record", "own"))).hasMessageContaining("not found");
        }
        assertThat(selector.select(WorkflowSubmitRequest.workflow(module, "record", "shared")).definition().getId()).isEqualTo(shared.getId());
        assertThatThrownBy(() -> selector.select(WorkflowSubmitRequest.workflow(module, "record", "own"))).hasMessageContaining("not found");
    }

    @Test void submittingSharedConfigurationPersistsCallerOwnedInstanceAndChildren() {
        var shared = configuration(null, null);
        var version = versions.query(net.ximatai.muyun.database.core.orm.Criteria.of()
                .eq("definitionId", shared.getId()), page).getFirst();
        try (var context = TenantContext.system("prepare shared workflow topology")) {
            for (var key : List.of("start", "approve", "end")) {
                var node = new WorkflowNodeDefinition(); node.setWorkflowVersionId(version.getId()); node.setNodeKey(key); node.setTitle(key);
                node.setNodeType(switch (key) { case "start" -> WorkflowNodeType.START;
                    case "approve" -> WorkflowNodeType.APPROVAL; default -> WorkflowNodeType.END; });
                node.setParticipantPolicyText("user:reviewer"); EntityLifecycle.prepareInsert(node, Instant.now()); nodeDefinitions.insert(node);
            }
            for (var pair : List.of(List.of("start", "approve"), List.of("approve", "end"))) {
                var link = new WorkflowLinkDefinition(); link.setWorkflowVersionId(version.getId());
                link.setRouteKey(pair.getFirst() + "_" + pair.getLast()); link.setTitle(link.getRouteKey()); link.setSourceNodeKey(pair.getFirst()); link.setTargetNodeKey(pair.getLast());
                EntityLifecycle.prepareInsert(link, Instant.now()); linkDefinitions.insert(link);
            }
        }
        String instanceId;
        try (var context = TenantContext.use("read-tenant-a")) {
            var result = submissions.submit(WorkflowSubmitRequest.workflowById(module, "shared-record", shared.getId()).withOperator("reviewer"));
            instanceId = result.draft().instance().getId();
            assertThat(instances.findById(instanceId).getTenantId()).isEqualTo("read-tenant-a");
            assertThat(runtime.renderBundle(instanceId).instance().getTenantId()).isEqualTo("read-tenant-a");
            assertThat(nodes.query(net.ximatai.muyun.database.core.orm.Criteria.of().eq("instanceId", instanceId), page))
                    .hasSize(3).allMatch(node -> "read-tenant-a".equals(node.getTenantId()));
            assertThat(routes.query(net.ximatai.muyun.database.core.orm.Criteria.of().eq("instanceId", instanceId), page))
                    .hasSize(2).allMatch(route -> "read-tenant-a".equals(route.getTenantId()));
            assertThat(tasks.query(net.ximatai.muyun.database.core.orm.Criteria.of().eq("instanceId", instanceId), page))
                    .hasSize(1).allMatch(task -> "read-tenant-a".equals(task.getTenantId()));
            assertThat(queries.instanceEvents(instanceId, page)).isNotEmpty()
                    .allMatch(event -> "read-tenant-a".equals(event.getTenantId()));
        }
        try (var context = TenantContext.use("read-tenant-b")) {
            assertThatThrownBy(() -> runtime.renderBundle(instanceId)).hasMessageContaining("not found");
            assertThat(queries.instanceTasks(instanceId, page)).isEmpty();
        }
    }

    @Test void sortingPublishedDefinitionsUsesPersistentOrderAndPreservesTenantPartition() {
        var first = configuration("read-tenant-a", "ordered_first");
        var second = configuration("read-tenant-a", "ordered_second");
        var foreign = configuration("read-tenant-b", "ordered_foreign");
        try (var context = TenantContext.use("read-tenant-a")) {
            first.setApprovalEnabled(true); first.setSortOrder(100); definitionService.update(first);
            second.setApprovalEnabled(true); second.setSortOrder(200); definitionService.update(second);
            var request = WorkflowSubmitRequest.approval(module, "shared-record");
            assertThat(selector.select(request).definition().getId()).isEqualTo(first.getId());
            definitionService.moveBefore(second.getId(), first.getId());
            assertThat(selector.select(request).definition().getId()).isEqualTo(second.getId());
            assertThat(definitions.findById(second.getId()).getSortOrder())
                    .isLessThan(definitions.findById(first.getId()).getSortOrder());
            assertThatThrownBy(() -> definitionService.moveBefore(second.getId(), foreign.getId()))
                    .isInstanceOf(RuntimeException.class);
        }
        try (var context = TenantContext.use("read-tenant-b")) {
            assertThat(definitions.findById(foreign.getId()).getVersion()).isEqualTo(foreign.getVersion());
        }
    }

    @Test void aliasFreeDefinitionsPersistIndependentlyAndIdSelectionCannotEscapeTheCallerScope() {
        var own = configuration("read-tenant-a", null);
        var another = configuration("read-tenant-a", null);
        var foreign = configuration("read-tenant-b", null);
        var shared = configuration(null, null);
        try (var context = TenantContext.use("read-tenant-a")) {
            var created = new WorkflowDefinition(); created.setModuleAlias(module); created.setApplicationAlias("test"); created.setTitle("无编码流程");
            definitionService.insert(created);
            var duplicateName = new WorkflowDefinition(); duplicateName.setModuleAlias(module); duplicateName.setApplicationAlias("test"); duplicateName.setTitle("无编码流程");
            definitionService.insert(duplicateName);
            assertThat(definitions.findById(created.getId()).getAlias()).isNull();
            assertThat(duplicateName.getId()).isNotEqualTo(created.getId());
            assertThat(selector.select(WorkflowSubmitRequest.workflowById(module, "record", own.getId())).definition().getId()).isEqualTo(own.getId());
            assertThat(selector.select(WorkflowSubmitRequest.workflowById(module, "record", another.getId())).definition().getId()).isEqualTo(another.getId());
            assertThat(selector.select(WorkflowSubmitRequest.workflowById(module, "record", shared.getId())).definition().getId()).isEqualTo(shared.getId());
            assertThatThrownBy(() -> selector.select(WorkflowSubmitRequest.workflowById(module, "record", foreign.getId()))).hasMessageContaining("not found");
            assertThatThrownBy(() -> selector.select(WorkflowSubmitRequest.workflowById("test.other", "record", own.getId()))).hasMessageContaining("not found");
        }
        assertThatThrownBy(() -> selector.select(WorkflowSubmitRequest.workflowById(module, "record", own.getId()))).hasMessageContaining("not found");
    }

    @Test void tenantOrderingNeverCompetesWithGlobalTemplateSortValues() {
        var shared = configuration(null, "shared_order");
        var first = configuration("read-tenant-a", "local_first");
        var second = configuration("read-tenant-a", "local_second");
        try (var context = TenantContext.use(null)) {
            shared.setApprovalEnabled(true); shared.setSortOrder(200); definitionService.update(shared);
        }
        try (var context = TenantContext.use("read-tenant-a")) {
            first.setApprovalEnabled(true); first.setSortOrder(300); definitionService.update(first);
            second.setApprovalEnabled(true); second.setSortOrder(400); definitionService.update(second);
            var request = WorkflowSubmitRequest.approval(module, "record");
            assertThat(definitionService.list(net.ximatai.muyun.database.core.orm.Criteria.of().eq("moduleAlias", module), page))
                    .extracting(WorkflowDefinition::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
            assertThat(selector.select(request).definition().getId()).isEqualTo(first.getId());
            definitionService.moveBefore(second.getId(), first.getId());
            assertThat(definitions.findById(second.getId()).getSortOrder()).isEqualTo(150);
            assertThat(selector.select(request).definition().getId()).isEqualTo(second.getId());
            definitionService.reorder(List.of(first.getId(), second.getId()));
            assertThat(selector.select(request).definition().getId()).isEqualTo(first.getId());
            first = definitions.findById(first.getId()); second = definitions.findById(second.getId());
            first.setDefaultDefinition(true); definitionService.update(first);
            second.setMatchExpression("false"); definitionService.update(second);
            // A global ordinary match outranks a tenant fallback.
            assertThat(selector.select(request).definition().getId()).isEqualTo(shared.getId());
        }
        try (var context = TenantContext.use(null)) {
            shared.setMatchExpression("false"); definitionService.update(shared);
        }
        try (var context = TenantContext.use("read-tenant-a")) {
            assertThat(selector.select(WorkflowSubmitRequest.approval(module, "record")).definition().getId()).isEqualTo(first.getId());
        }
        try (var context = TenantContext.use("read-tenant-b")) {
            assertThatThrownBy(() -> selector.select(WorkflowSubmitRequest.approval(module, "record"))).hasMessageContaining("not found");
        }
        try (var context = TenantContext.use(null)) {
            assertThat(definitions.findById(shared.getId()).getSortOrder()).isEqualTo(200);
        }
    }

    @Test void organizationDistanceAndOrdinaryMatchesRemainAheadOfSourcePrecedence() {
        var shared = configuration(null, "shared_near");
        var local = configuration("read-tenant-a", "local_fallback");
        try (var context = TenantContext.use(null)) {
            shared.setApprovalEnabled(true); shared.setOrganizationId("org"); shared.setSortOrder(1);
            definitionService.update(shared);
        }
        var request = WorkflowSubmitRequest.approval(module, "record").withAuthOrgId("org");
        try (var context = TenantContext.use("read-tenant-a")) {
            local.setApprovalEnabled(true); local.setSortOrder(900); definitionService.update(local);
            assertThat(selector.select(request).definition().getId()).isEqualTo(shared.getId());
            local.setOrganizationId("org"); local.setDefaultDefinition(true); definitionService.update(local);
            assertThat(selector.select(request).definition().getId()).isEqualTo(shared.getId());
        }
        try (var context = TenantContext.use(null)) {
            shared.setDefaultDefinition(true); definitionService.update(shared);
        }
        try (var context = TenantContext.use("read-tenant-a")) {
            assertThat(selector.select(request).definition().getId()).isEqualTo(local.getId());
        }
    }

    private WorkflowDefinition configuration(String tenant, String alias) {
        try (var context = TenantContext.use(tenant)) {
            var definition = new WorkflowDefinition(); definition.setApplicationAlias("test"); definition.setModuleAlias(module);
            definition.setAlias(alias); definition.setTitle(alias == null ? "无编码流程" : alias); definition.setEnabled(true); definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED);
            definition.setApprovalEnabled(false); definition.setCurrentVersionNo(1); EntityLifecycle.prepareInsert(definition, Instant.now()); definitions.insert(definition);
            var version = new WorkflowVersion(); version.setDefinitionId(definition.getId()); version.setVersionNo(1);
            version.setPublishStatus(WorkflowPublishStatus.PUBLISHED); version.setSnapshotText("{}"); EntityLifecycle.prepareInsert(version, Instant.now()); versions.insert(version);
            return definition;
        }
    }

    private void assertVisible(Fixture expected) {
        assertThat(runtime.todoCards("reviewer", page)).filteredOn(card -> module.equals(card.moduleAlias()))
                .extracting(WorkflowWorkbenchCard::instanceId).containsExactly(expected.instance().getId());
        assertThat(runtime.trackingCards("reviewer", page)).filteredOn(card -> module.equals(card.moduleAlias()))
                .extracting(WorkflowWorkbenchCard::instanceId).containsExactly(expected.instance().getId());
        assertThat(queries.myTodo("reviewer", page)).filteredOn(task -> List.of(first.instance().getId(), second.instance().getId(), global.instance().getId()).contains(task.getInstanceId()))
                .extracting(WorkflowTask::getId).containsExactly(expected.task().getId());
        assertThat(admin.queryCurrentInstances(WorkflowAdminInstanceQueryRequest.empty(), page)).filteredOn(view -> module.equals(view.moduleAlias()))
                .extracting(WorkflowAdminInstanceView::instanceId).containsExactly(expected.instance().getId());
        assertThat(history.queryRecordHistory(module, "record", page)).extracting(WorkflowHistoryInstance::getId).containsExactly(expected.history().getId());
        assertThat(history.queryAdminHistory(module, "record", page)).extracting(WorkflowHistoryInstance::getId).containsExactly(expected.history().getId());
        assertThat(status.status(WorkflowSubmitRequest.approval(module, "record")).instanceId()).isEqualTo(expected.instance().getId());
        assertThat(queries.instanceEvents(expected.instance().getId(), page)).hasSize(1)
                .allMatch(event -> java.util.Objects.equals(event.getTenantId(), expected.instance().getTenantId()));
    }

    private Fixture fixture(String tenant) {
        try (var context = TenantContext.use(tenant)) {
            var now = Instant.now();
            var instance = new WorkflowInstance(); instance.setDefinitionId("global-definition"); instance.setWorkflowVersionId("global-version");
            instance.setVersionNo(1); instance.setApprovalEnabled(true); instance.setApprovalStatus(WorkflowApprovalStatus.PROCESSING); instance.setModuleAlias(module); instance.setRecordId("record");
            instance.setStartedBy("reviewer"); instance.setStartedAt(now); instance.setSnapshotText("{}");
            EntityLifecycle.prepareInsert(instance, now); instances.insert(instance);
            var node = new WorkflowNodeInstance(); node.setInstanceId(instance.getId()); node.setNodeKey("approve");
            node.setNodeRunId(UUID.randomUUID().toString()); node.setNodeType(WorkflowNodeType.APPROVAL); node.setNodeStatus(WorkflowNodeStatus.ACTIVE);
            EntityLifecycle.prepareInsert(node, now); nodes.insert(node);
            var task = new WorkflowTask(); task.setInstanceId(instance.getId()); task.setNodeInstanceId(node.getId());
            task.setTaskKind(WorkflowTaskKind.APPROVAL); task.setAssigneeId("reviewer"); EntityLifecycle.prepareInsert(task, now); tasks.insert(task);
            var event = new WorkflowEvent(); event.setInstanceId(instance.getId()); event.setEventType(WorkflowEventType.INSTANCE_STARTED);
            event.setOccurredAt(now); EntityLifecycle.prepareInsert(event, now); events.insert(event);
            var archived = new WorkflowHistoryInstance(); archived.setDefinitionId("global-definition"); archived.setWorkflowVersionId("global-version");
            archived.setVersionNo(1); archived.setModuleAlias(module); archived.setRecordId("record"); archived.setStartedBy("reviewer");
            archived.setStartedAt(now); archived.setInstanceStatus(WorkflowInstanceStatus.COMPLETED); archived.setArchiveReason(WorkflowArchiveReason.RESET);
            archived.setArchivedAt(now); archived.setSnapshotText("{}"); EntityLifecycle.prepareInsert(archived, now); histories.insert(archived);
            return new Fixture(instance, task, archived);
        }
    }
    private record Fixture(WorkflowInstance instance, WorkflowTask task, WorkflowHistoryInstance history) {}
    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.context.annotation.Import(WorkflowRepositoryTestConfiguration.class)
    static class Host {}

}
