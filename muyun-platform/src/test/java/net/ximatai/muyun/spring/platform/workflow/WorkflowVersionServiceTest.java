package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowVersionServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowDefinitionService definitionService = new WorkflowDefinitionService(new TestMemoryDao<>());
    private final WorkflowVersionService service = new WorkflowVersionService(new TestMemoryDao<>(),
            definitionService);

    @Test
    void shouldKeepPublishedVersionWithoutActionSideEffect() {
        WorkflowDefinition definition = definition();
        definitionService.insert(definition);
        WorkflowVersion version = version(definition.getId(), WorkflowPublishStatus.PUBLISHED);

        service.insert(version);

        assertThat(service.select(version.getId()).getPublishStatus()).isEqualTo(WorkflowPublishStatus.PUBLISHED);
    }

    @Test
    void shouldInsertDraftVersion() {
        WorkflowDefinition definition = definition();
        definitionService.insert(definition);
        WorkflowVersion version = version(definition.getId(), WorkflowPublishStatus.DRAFT);

        service.insert(version);

        assertThat(service.select(version.getId()).getPublishStatus()).isEqualTo(WorkflowPublishStatus.DRAFT);
    }

    private WorkflowDefinition definition() {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("def-1");
        definition.setApplicationAlias("sales");
        definition.setModuleAlias("sales.contract");
        definition.setAlias("sync");
        definition.setTitle("同步流程");
        definition.setApprovalEnabled(false);
        definition.setActionCode("syncWorkflow");
        return definition;
    }

    private WorkflowVersion version(String definitionId, WorkflowPublishStatus status) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId("ver-1");
        version.setDefinitionId(definitionId);
        version.setVersionNo(1);
        version.setPublishStatus(status);
        return version;
    }
}
