package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

    @Test
    void requiresAnExplicitActionCodeForNonApprovalCreatesAndUpdates() {
        var missing = definition(null, null); missing.setApprovalEnabled(false);
        assertThatThrownBy(() -> service.insert(missing)).hasMessageContaining("非审批流程必须配置模块动作编码");
        missing.setActionCode("   ");
        assertThatThrownBy(() -> service.beforeUpdate(missing)).hasMessageContaining("非审批流程必须配置模块动作编码");
        missing.setActionCode("Invalid Action");
        assertThatThrownBy(() -> service.insert(missing)).isInstanceOf(IllegalArgumentException.class);
        missing.setActionCode("runTask");
        service.insert(missing);
        assertThat(missing.getAlias()).isNull();
        assertThat(missing.getActionCode()).isEqualTo("runTask");
    }

    @Test
    void permitsDraftActionCodeRepairButKeepsPublishedActionIdentityStable() {
        var stored = definition(null, "runTask"); service.insert(stored);
        var changed = definition(null, "runAnotherTask"); changed.setId(stored.getId());
        assertThatCode(() -> service.beforeUpdate(changed)).doesNotThrowAnyException();
        stored.setCurrentVersionNo(1);
        assertThatThrownBy(() -> service.beforeUpdate(changed)).hasMessageContaining("已发布流程不能修改模块动作编码");
        changed.setActionCode("runTask");
        assertThatCode(() -> service.beforeUpdate(changed)).doesNotThrowAnyException();
    }

    private WorkflowDefinition definition(String alias, String actionCode) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setApplicationAlias("sales");
        definition.setModuleAlias("sales.contract");
        definition.setAlias(alias);
        definition.setTitle(alias == null || alias.isBlank() ? "无编码流程" : alias);
        definition.setApprovalEnabled(actionCode == null);
        definition.setActionCode(actionCode);
        return definition;
    }
}
