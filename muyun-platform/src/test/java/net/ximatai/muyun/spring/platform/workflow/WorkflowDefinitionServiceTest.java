package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void allowsMultipleDefinitionsWithoutInventingAliasesAndStillValidatesExplicitAliases() {
        var first = definition(null, null);
        var second = definition("  ", null);
        service.insert(first); service.insert(second);
        assertThat(first.getAlias()).isNull(); assertThat(second.getAlias()).isNull();
        assertThat(first.getId()).isNotBlank().isNotEqualTo(second.getId());
        service.insert(definition("legacy", null));
        assertThatThrownBy(() -> service.insert(definition("legacy", null))).isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> service.insert(definition("Invalid Alias", null))).isInstanceOf(RuntimeException.class);
    }

    private WorkflowDefinition definition(String alias, String actionCode) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setApplicationAlias("sales");
        definition.setModuleAlias("sales.contract");
        definition.setAlias(alias);
        definition.setTitle(alias == null || alias.isBlank() ? "无编码流程" : alias);
        definition.setApprovalEnabled(false);
        definition.setActionCode(actionCode);
        return definition;
    }
}
