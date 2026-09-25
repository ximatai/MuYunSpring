package net.ximatai.muyun.spring.platform.generation;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.platform.module.ModuleActionBindingType;
import net.ximatai.muyun.spring.platform.module.ModuleActionContributionRegistrar;
import net.ximatai.muyun.spring.platform.module.ModuleActionSourceType;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationModuleActionContributorTest {
    private final PlatformModuleService moduleService = new PlatformModuleService(new TestMemoryDao<>(), event -> {});
    private final PlatformModuleActionService actionService =
            new PlatformModuleActionService(new TestMemoryDao<>(), moduleService);
    private final ModuleActionContributionRegistrar registrar = new ModuleActionContributionRegistrar(actionService);
    private final GenerationModuleActionContributor contributor = new GenerationModuleActionContributor(registrar);

    @Test
    void shouldRegisterGenerateRecordActionWithManagedSourceAndBinding() {
        moduleService.insert(module("sales.contract"));
        RecordGenerationRule rule = rule();

        contributor.syncRuleAction(rule);

        PlatformModuleAction action = actionService.findByModuleAliasAndActionCode("sales.contract", "generateInvoice");
        assertThat(action).isNotNull();
        assertThat(action.getTitle()).isEqualTo("生成发票");
        assertThat(action.getCategory()).isEqualTo(EntityActionCategory.GENERATE);
        assertThat(action.getExecutorType()).isEqualTo(EntityActionExecutorType.GENERATE);
        assertThat(action.getExecutorKey()).isEqualTo(RecordGenerationActionExecutor.EXECUTOR_KEY);
        assertThat(action.getActionLevel()).isEqualTo(EntityActionLevel.RECORD);
        assertThat(action.getAccessMode()).isEqualTo(EntityActionAccessMode.AUTH_REQUIRED);
        assertThat(action.getActionAuth()).isTrue();
        assertThat(action.getDataAuth()).isTrue();
        assertThat(action.getAvailableExpression()).isNull();
        assertThat(action.getSourceType()).isEqualTo(ModuleActionSourceType.RECORD_GENERATION_RULE);
        assertThat(action.getSourceId()).isEqualTo("rule-1");
        assertThat(action.getBindingType()).isEqualTo(ModuleActionBindingType.RECORD_GENERATION_RULE);
        assertThat(action.getBindingId()).isEqualTo("rule-1");
        assertThat(action.getBindingAlias()).isEqualTo("generateInvoice");
        assertThat(action.getSystemManaged()).isTrue();
        assertThat(action.getEnabled()).isTrue();
    }

    @Test
    void shouldDisableActionsByGenerationRuleSource() {
        moduleService.insert(module("sales.contract"));
        contributor.syncRuleAction(rule());

        contributor.disableRuleAction("rule-1");

        PlatformModuleAction action = actionService.findByModuleAliasAndActionCode("sales.contract", "generateInvoice");
        assertThat(action.getEnabled()).isFalse();
    }

    @Test
    void shouldRejectGenerationActionContributionWithoutRuleId() {
        RecordGenerationRule rule = rule();
        rule.setId(null);

        assertThatThrownBy(() -> contributor.contribution(rule))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires rule id");
    }

    private RecordGenerationRule rule() {
        RecordGenerationRule rule = new RecordGenerationRule();
        rule.setId("rule-1");
        rule.setSourceModuleAlias("sales.contract");
        rule.setTargetModuleAlias("finance.invoice");
        rule.setActionCode("generateInvoice");
        rule.setTitle("生成发票");
        rule.setGenerationCondition("status == 'approved'");
        rule.setEnabled(Boolean.TRUE);
        return rule;
    }

    private PlatformModule module(String alias) {
        PlatformModule module = new PlatformModule();
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setAlias(alias);
        module.setTitle(alias);
        return module;
    }
}
