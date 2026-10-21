package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowDefinitionSelectorTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowDefinitionService definitionService = new WorkflowDefinitionService(new TestMemoryDao<>());
    private final WorkflowVersionService versionService = new WorkflowVersionService(new TestMemoryDao<>(),
            definitionService);
    private final WorkflowNodeDefinitionDao nodeDao = mock(WorkflowNodeDefinitionDao.class);
    private final WorkflowLinkDefinitionDao linkDao = mock(WorkflowLinkDefinitionDao.class);
    private final WorkflowDefinitionSelector selector = new WorkflowDefinitionSelector(definitionService,
                versionService,
                nodeDao,
                linkDao,
                new WorkflowConditionService(WorkflowTestSupport.facts()),
                java.util.Optional.empty());

    @Test
    void shouldSelectPublishedDefinitionLatestPublishedVersionAndRuntimeGraph() {
        WorkflowDefinition draft = definition("sales.contract", "draft", true, WorkflowDefinitionStatus.DRAFT);
        WorkflowDefinition published = definition("sales.contract", "approve", true, WorkflowDefinitionStatus.PUBLISHED);
        definitionService.insert(draft);
        definitionService.insert(published);
        WorkflowVersion oldVersion = version(published, 1, WorkflowPublishStatus.PUBLISHED);
        WorkflowVersion newVersion = version(published, 2, WorkflowPublishStatus.PUBLISHED);
        versionService.insert(oldVersion);
        versionService.insert(newVersion);
        WorkflowNodeDefinition start = node(newVersion, "start", WorkflowNodeType.START);
        WorkflowLinkDefinition link = link(newVersion, "r1", "start", "end");
        when(nodeDao.query(any(), any(), any())).thenReturn(List.of(start));
        when(linkDao.query(any(), any(), any())).thenReturn(List.of(link));

        WorkflowDefinitionSelection selection = selector.select(WorkflowSubmitRequest.approval("sales.contract", "c1"));

        assertThat(selection.definition().getAlias()).isEqualTo("approve");
        assertThat(selection.version().getVersionNo()).isEqualTo(2);
        assertThat(selection.nodes()).containsExactly(start);
        assertThat(selection.links()).containsExactly(link);
    }

    @Test
    void shouldUseRequestedDefinitionAliasForNonApprovalWorkflow() {
        WorkflowDefinition first = definition("sales.contract", "sync", false, WorkflowDefinitionStatus.PUBLISHED);
        WorkflowDefinition second = definition("sales.contract", "notify", false, WorkflowDefinitionStatus.PUBLISHED);
        definitionService.insert(first);
        definitionService.insert(second);
        WorkflowVersion version = version(second, 1, WorkflowPublishStatus.PUBLISHED);
        versionService.insert(version);
        when(nodeDao.query(any(), any(), any())).thenReturn(List.of(node(version, "start", WorkflowNodeType.START)));
        when(linkDao.query(any(), any(), any())).thenReturn(List.of());

        WorkflowDefinitionSelection selection = selector.select(
                WorkflowSubmitRequest.workflow("sales.contract", "c1", "notify"));

        assertThat(selection.definition().getAlias()).isEqualTo("notify");
    }

    @Test
    void shouldRejectNonApprovalWorkflowWithoutAnExplicitDefinition() {
        assertThatThrownBy(() -> selector.select(new WorkflowSubmitRequest(
                "sales.contract", "c1", false, null, "user-1", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("definition id or alias is required");
    }

    @Test
    void listOrderSelectsFirstMatchAndMovingAWorkflowChangesFutureSelection() {
        var first = definition("sales.contract", "approvea", true, WorkflowDefinitionStatus.PUBLISHED);
        var second = definition("sales.contract", "approveb", true, WorkflowDefinitionStatus.PUBLISHED);
        definitionService.insert(first);
        definitionService.insert(second);
        versionService.insert(version(first, 1, WorkflowPublishStatus.PUBLISHED));
        versionService.insert(version(second, 1, WorkflowPublishStatus.PUBLISHED));
        when(nodeDao.query(any(), any(), any())).thenReturn(List.of());
        when(linkDao.query(any(), any(), any())).thenReturn(List.of());
        var request = WorkflowSubmitRequest.approval("sales.contract", "c1");
        second.setMatchExpression("{amount} >"); definitionService.update(second);
        assertThat(selector.select(request).definition().getId()).isEqualTo(first.getId());
        second.setMatchExpression(null); definitionService.update(second);
        definitionService.moveBefore(second.getId(), first.getId());
        assertThat(selector.select(request).definition().getId()).isEqualTo(second.getId());
        first.setMatchExpression("false");
        definitionService.update(first);
        definitionService.moveBefore(first.getId(), second.getId());
        assertThat(selector.select(request).definition().getId()).isEqualTo(second.getId());
    }

    @Test
    void organizationDistanceAndFallbackOutrankListOrder() {
        var fallback = definition("sales.contract", "fallback", true, WorkflowDefinitionStatus.PUBLISHED);
        fallback.setDefaultDefinition(true);
        var global = definition("sales.contract", "global", true, WorkflowDefinitionStatus.PUBLISHED);
        var local = definition("sales.contract", "local", true, WorkflowDefinitionStatus.PUBLISHED);
        local.setOrganizationId("org");
        for (var definition : List.of(fallback, global, local)) {
            definitionService.insert(definition);
            versionService.insert(version(definition, 1, WorkflowPublishStatus.PUBLISHED));
        }
        when(nodeDao.query(any(), any(), any())).thenReturn(List.of());
        when(linkDao.query(any(), any(), any())).thenReturn(List.of());
        assertThat(selector.select(WorkflowSubmitRequest.approval("sales.contract", "c1").withAuthOrgId("org"))
                .definition().getId()).isEqualTo(local.getId());
        assertThat(selector.select(WorkflowSubmitRequest.approval("sales.contract", "c1"))
                .definition().getId()).isEqualTo(global.getId());
        global.setMatchExpression("false"); definitionService.update(global);
        assertThat(selector.select(WorkflowSubmitRequest.approval("sales.contract", "c1"))
                .definition().getId()).isEqualTo(fallback.getId());
    }

    @Test
    void shouldRejectWhenPublishedVersionMissing() {
        WorkflowDefinition definition = definition("sales.contract", "approve", true, WorkflowDefinitionStatus.PUBLISHED);
        definitionService.insert(definition);

        assertThatThrownBy(() -> selector.select(WorkflowSubmitRequest.approval("sales.contract", "c1")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("published workflow version not found");
    }

    @Test
    void globalConfigurationMatchingReadsBusinessFactsInCallerTenantAndRestoresItsContext() {
        var definition = definition("sales.contract", "global", true, WorkflowDefinitionStatus.PUBLISHED);
        definition.setMatchExpression("{eligible} == true"); definitionService.insert(definition);
        versionService.insert(version(definition, 1, WorkflowPublishStatus.PUBLISHED));
        when(nodeDao.query(any(), any(), any())).thenReturn(List.of());
        when(linkDao.query(any(), any(), any())).thenReturn(List.of());
        var read = new java.util.concurrent.atomic.AtomicBoolean();
        var scopedSelector = new WorkflowDefinitionSelector(definitionService, versionService, nodeDao, linkDao,
                new WorkflowConditionService((module, id) -> {
                    assertThat(net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId()).contains("tenant-a");
                    assertThat(module).isEqualTo("sales.contract"); assertThat(id).isEqualTo("c1");
                    read.set(true); return java.util.Map.of("eligible", true);
                }), java.util.Optional.empty());
        try (var context = net.ximatai.muyun.spring.common.tenant.TenantContext.use("tenant-a")) {
            assertThat(scopedSelector.select(WorkflowSubmitRequest.approval("sales.contract", "c1")).definition().getId())
                    .isEqualTo(definition.getId());
            assertThat(read).isTrue();
            assertThat(net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId()).contains("tenant-a");
        }
    }

    @Test
    void selectsAliasFreeNonApprovalDefinitionsByIdAndKeepsMatchingBoundaries() {
        var first = definition("sales.contract", null, false, WorkflowDefinitionStatus.PUBLISHED);
        var second = definition("sales.contract", null, false, WorkflowDefinitionStatus.PUBLISHED);
        definitionService.insert(first); definitionService.insert(second);
        versionService.insert(version(first, 1, WorkflowPublishStatus.PUBLISHED));
        versionService.insert(version(second, 1, WorkflowPublishStatus.PUBLISHED));
        when(nodeDao.query(any(), any(), any())).thenReturn(List.of());
        when(linkDao.query(any(), any(), any())).thenReturn(List.of());
        var request = WorkflowSubmitRequest.workflowById("sales.contract", "c1", second.getId());
        assertThat(selector.select(request).definition().getId()).isEqualTo(second.getId());
        assertThatThrownBy(() -> selector.select(WorkflowSubmitRequest.workflowById("sales.invoice", "c1", second.getId())))
                .hasMessageContaining("not found");
        second.setOrganizationId("org-a"); definitionService.update(second);
        assertThatThrownBy(() -> selector.select(request)).hasMessageContaining("not found");
        assertThat(selector.select(request.withAuthOrgId("org-a")).definition().getId()).isEqualTo(second.getId());
        second.setMatchExpression("false"); definitionService.update(second);
        assertThatThrownBy(() -> selector.select(request.withAuthOrgId("org-a"))).hasMessageContaining("not found");
    }

    @Test
    void approvalSelectionDoesNotRequireAnAliasAndExplicitIdsCannotSelectDraftOrDisabledDefinitions() {
        var definition = definition("sales.contract", null, true, WorkflowDefinitionStatus.PUBLISHED);
        definitionService.insert(definition); versionService.insert(version(definition, 1, WorkflowPublishStatus.PUBLISHED));
        when(nodeDao.query(any(), any(), any())).thenReturn(List.of());
        when(linkDao.query(any(), any(), any())).thenReturn(List.of());
        assertThat(selector.select(WorkflowSubmitRequest.approval("sales.contract", "c1")).definition().getAlias()).isNull();
        var request = new WorkflowSubmitRequest("sales.contract", "c1", true, null, null, null, null, null, null, List.of(), null, definition.getId());
        definition.setDefinitionStatus(WorkflowDefinitionStatus.DRAFT); definitionService.update(definition);
        assertThatThrownBy(() -> selector.select(request)).hasMessageContaining("not found");
        definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED); definition.setEnabled(false); definitionService.update(definition);
        assertThatThrownBy(() -> selector.select(request)).hasMessageContaining("not found");
        assertThatThrownBy(() -> selector.select(new WorkflowSubmitRequest("sales.contract", "c1", false, "legacy", null, null, null, null, null, List.of(), null, "id")))
                .hasMessageContaining("not both");
    }

    private int definitionSequence;

    private WorkflowDefinition definition(String moduleAlias, String alias, boolean approvalEnabled,
                                          WorkflowDefinitionStatus status) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setApplicationAlias(moduleAlias.substring(0, moduleAlias.indexOf('.')));
        definition.setModuleAlias(moduleAlias);
        definition.setAlias(alias);
        definition.setTitle(alias == null ? "无编码流程" : alias);
        definition.setApprovalEnabled(approvalEnabled);
        if (!approvalEnabled) definition.setActionCode("runFlow" + (++definitionSequence));
        definition.setDefinitionStatus(status);
        definition.setEnabled(Boolean.TRUE);
        return definition;
    }

    private WorkflowVersion version(WorkflowDefinition definition, int versionNo, WorkflowPublishStatus status) {
        WorkflowVersion version = new WorkflowVersion();
        version.setDefinitionId(definition.getId());
        version.setVersionNo(versionNo);
        version.setPublishStatus(status);
        version.setSnapshotText("{}");
        return version;
    }

    private WorkflowNodeDefinition node(WorkflowVersion version, String key, WorkflowNodeType type) {
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setWorkflowVersionId(version.getId());
        node.setNodeKey(key);
        node.setNodeType(type);
        return node;
    }

    private WorkflowLinkDefinition link(WorkflowVersion version, String key, String source, String target) {
        WorkflowLinkDefinition link = new WorkflowLinkDefinition();
        link.setWorkflowVersionId(version.getId());
        link.setRouteKey(key);
        link.setSourceNodeKey(source);
        link.setTargetNodeKey(target);
        return link;
    }
}
