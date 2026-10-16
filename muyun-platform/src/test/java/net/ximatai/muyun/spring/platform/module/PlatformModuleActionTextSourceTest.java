package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlatformModuleActionTextSourceTest {
    @Test
    void durableContributorIdentityDistinguishesGeneratedTitlesFromEditableConfiguration() {
        var row = new PlatformModuleAction();
        row.setModuleAlias("test.contract");
        row.setActionCode("update");
        row.setTitle(PlatformAction.UPDATE.title());
        row.setSourceType(ModuleActionSourceType.DYNAMIC_MODULE);
        row.setSystemManaged(true);
        assertThat(row.titleSource().sourceKind()).isEqualTo(TextReference.SourceKind.CODE);
        assertThat(row.titleSource().sourceId()).isEqualTo(PlatformAction.UPDATE.titleKey());
        row.setSystemManaged(false);
        assertThat(row.titleSource().sourceKind()).isEqualTo(TextReference.SourceKind.CONFIG);
        assertThat(row.titleSource().sourceId()).isEqualTo("config/action/test.contract/update/title");
        row.setSystemManaged(true);
        row.setSourceType(ModuleActionSourceType.WORKFLOW_DEFINITION);
        assertThat(row.titleSource().sourceKind()).isEqualTo(TextReference.SourceKind.CONFIG);
        row.setSourceType(ModuleActionSourceType.WORKFLOW_RUNTIME);
        row.setActionCode("submitApproval");
        row.setTitle("提交审批");
        assertThat(row.titleSource().sourceKind()).isEqualTo(TextReference.SourceKind.CODE);
        assertThat(row.titleSource().sourceId()).isEqualTo("platform.workflow.action.submitApproval.title");
    }
}
