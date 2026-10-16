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
    void shouldRejectNonApprovalWorkflowWithoutDefinitionAlias() {
        assertThatThrownBy(() -> selector.select(new WorkflowSubmitRequest(
                "sales.contract", "c1", false, null, "user-1", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("definition alias is required");
    }

    @Test
    void shouldRejectAmbiguousApprovalDefinitionsWhenAliasIsNotProvided() {
        WorkflowDefinition first = definition("sales.contract", "approvea", true, WorkflowDefinitionStatus.PUBLISHED);
        WorkflowDefinition second = definition("sales.contract", "approveb", true, WorkflowDefinitionStatus.PUBLISHED);
        definitionService.insert(first);
        definitionService.insert(second);

        assertThatThrownBy(() -> selector.select(new WorkflowSubmitRequest(
                "sales.contract", "c1", true, " ", "user-1", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("multiple approval workflow definitions matched");
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

    private WorkflowDefinition definition(String moduleAlias, String alias, boolean approvalEnabled,
                                          WorkflowDefinitionStatus status) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setApplicationAlias(moduleAlias.substring(0, moduleAlias.indexOf('.')));
        definition.setModuleAlias(moduleAlias);
        definition.setAlias(alias);
        definition.setApprovalEnabled(approvalEnabled);
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
