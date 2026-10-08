package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowDefinitionService service = new WorkflowDefinitionService(new TestMemoryDao<>());

    @Test
    void shouldRejectDuplicateNonApprovalWorkflowActionCodeWithinModule() {
        service.insert(definition("sync", "syncWorkflow"));

        assertThatThrownBy(() -> service.insert(definition("notify", "syncWorkflow")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("workflow actionCode must be unique within module");
    }

    private WorkflowDefinition definition(String alias, String actionCode) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setApplicationAlias("sales");
        definition.setModuleAlias("sales.contract");
        definition.setAlias(alias);
        definition.setTitle(alias);
        definition.setApprovalEnabled(false);
        definition.setActionCode(actionCode);
        return definition;
    }
}
