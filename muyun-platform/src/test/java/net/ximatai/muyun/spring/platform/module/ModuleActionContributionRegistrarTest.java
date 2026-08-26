package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleActionContributionRegistrarTest {
    private final TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformModuleAction> actionDao = new TestMemoryDao<>();
    private final PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
    private final PlatformModuleActionService actionService = new PlatformModuleActionService(actionDao, moduleService);
    private final ModuleActionContributionRegistrar registrar = new ModuleActionContributionRegistrar(actionService);

    @Test
    void shouldRegisterContributedModuleActionWithBinding() {
        moduleService.insert(module("sales.contract"));

        registrar.register(contribution("syncWorkflow", "def-1", "ver-1", "sync"));

        PlatformModuleAction action = actionService.findByModuleAliasAndActionCode("sales.contract", "syncWorkflow");
        assertThat(action).isNotNull();
        assertThat(action.getTitle()).isEqualTo("同步流程");
        assertThat(action.getCategory()).isEqualTo(EntityActionCategory.WORKFLOW);
        assertThat(action.getExecutorKey()).isEqualTo("platform.workflow");
        assertThat(action.getSourceType()).isEqualTo(ModuleActionSourceType.WORKFLOW_DEFINITION);
        assertThat(action.getSourceId()).isEqualTo("def-1");
        assertThat(action.getSourceVersionId()).isEqualTo("ver-1");
        assertThat(action.getBindingType()).isEqualTo(ModuleActionBindingType.WORKFLOW_DEFINITION);
        assertThat(action.getBindingId()).isEqualTo("def-1");
        assertThat(action.getBindingAlias()).isEqualTo("sync");
        assertThat(action.getSystemManaged()).isTrue();
    }

    @Test
    void shouldUpdateExistingContributedModuleAction() {
        moduleService.insert(module("sales.contract"));
        registrar.register(contribution("syncWorkflow", "def-1", "ver-1", "sync"));

        registrar.register(contribution("syncWorkflow", "def-1", "ver-2", "sync"));

        PlatformModuleAction action = actionService.findByModuleAliasAndActionCode("sales.contract", "syncWorkflow");
        assertThat(action.getSourceVersionId()).isEqualTo("ver-2");
        assertThat(actionDao.count(Criteria.of())).isEqualTo(1);
    }

    @Test
    void shouldKeepPermissionGovernanceOverrideWhenContributionIsRegisteredAgain() {
        moduleService.insert(module("sales.contract"));
        registrar.register(contribution("syncWorkflow", "def-1", "ver-1", "sync"));

        PlatformModuleAction action = actionService.findByModuleAliasAndActionCode("sales.contract", "syncWorkflow");
        action.setAccessModeOverride(EntityActionAccessMode.ANONYMOUS_ALLOWED);
        action.setActionAuthOverride(false);
        action.setDataAuthOverride(false);
        action.setDefaultGrantPolicyOverride(ActionDefaultGrantPolicy.ANY_LOGIN_USER);
        actionService.update(action);

        registrar.register(contribution("syncWorkflow", "def-1", "ver-2", "sync"));

        PlatformModuleAction reloaded = actionService.findByModuleAliasAndActionCode("sales.contract", "syncWorkflow");
        assertThat(reloaded.effectiveAccessMode()).isEqualTo(EntityActionAccessMode.ANONYMOUS_ALLOWED);
        assertThat(reloaded.effectiveActionAuth()).isFalse();
        assertThat(reloaded.effectiveDefaultGrantPolicy()).isEqualTo(ActionDefaultGrantPolicy.ANY_LOGIN_USER);
        assertThat(reloaded.getSourceVersionId()).isEqualTo("ver-2");
    }

    @Test
    void shouldExposeManagedActionGovernanceUpdateButRejectDeclarationAndLifecycleChanges() {
        moduleService.insert(module("sales.contract"));
        registrar.register(contribution("syncWorkflow", "def-1", "ver-1", "sync"));
        PlatformModuleAction managed = actionService.findByModuleAliasAndActionCode("sales.contract", "syncWorkflow");

        assertThat(actionService.ordinaryRecordActionAvailability("update", managed)).isEmpty();
        assertThat(actionService.ordinaryRecordActionAvailability("enable", managed))
                .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管记录不可变更启用状态"));

        PlatformModuleAction forbidden = new PlatformModuleAction();
        forbidden.setId(managed.getId());
        forbidden.setVersion(managed.getVersion());
        forbidden.setTitle("不允许修改声明");

        assertThatThrownBy(() -> actionService.update(forbidden))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
    }

    @Test
    void shouldRequireCurrentVersionWhenClearingPermissionGovernanceOverrides() {
        moduleService.insert(module("sales.contract"));
        registrar.register(contribution("syncWorkflow", "def-1", "ver-1", "sync"));
        PlatformModuleAction action = actionService.findByModuleAliasAndActionCode("sales.contract", "syncWorkflow");
        Integer staleVersion = action.getVersion();
        action.setActionAuthOverride(false);
        actionService.update(action);

        assertThatThrownBy(() -> actionService.clearPermissionGovernanceOverrides(
                "sales.contract", action.getId(), staleVersion))
                .isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void shouldRejectActionCodeConflictWithDifferentSource() {
        moduleService.insert(module("sales.contract"));
        PlatformModuleAction existing = new PlatformModuleAction();
        existing.setModuleAlias("sales.contract");
        existing.setActionCode("syncWorkflow");
        existing.setTitle("已有动作");
        existing.setSystemManaged(Boolean.FALSE);
        actionService.insert(existing);

        assertThatThrownBy(() -> registrar.register(contribution("syncWorkflow", "def-1", "ver-1", "sync")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("module action contribution conflicts");
    }

    @Test
    void shouldDisableStaleActionFromSameSourceWhenActionCodeChanges() {
        moduleService.insert(module("sales.contract"));
        registrar.register(contribution("oldWorkflow", "def-1", "ver-1", "sync"));

        registrar.register(contribution("syncWorkflow", "def-1", "ver-2", "sync"));

        assertThat(actionService.findByModuleAliasAndActionCode("sales.contract", "oldWorkflow").getEnabled())
                .isFalse();
        assertThat(actionService.findByModuleAliasAndActionCode("sales.contract", "syncWorkflow").getEnabled())
                .isTrue();
    }

    @Test
    void shouldRegisterMultipleActionsFromSameSourceWithoutDisablingEachOther() {
        moduleService.insert(module("sales.contract"));

        registrar.registerAll(List.of(
                contribution("approve", "runtime-1", "ver-1", null),
                contribution("reject", "runtime-1", "ver-1", null)));

        PlatformModuleAction approve = actionService.findByModuleAliasAndActionCode("sales.contract", "approve");
        PlatformModuleAction reject = actionService.findByModuleAliasAndActionCode("sales.contract", "reject");
        assertThat(approve.getEnabled()).isTrue();
        assertThat(reject.getEnabled()).isTrue();

        registrar.registerAll(List.of(contribution("approve", "runtime-1", "ver-2", null)));

        assertThat(actionService.findByModuleAliasAndActionCode("sales.contract", "approve").getEnabled()).isTrue();
        assertThat(actionService.findByModuleAliasAndActionCode("sales.contract", "reject").getEnabled()).isFalse();
    }

    @Test
    void shouldDisableAllActionsFromSource() {
        moduleService.insert(module("sales.contract"));
        registrar.register(contribution("syncWorkflow", "def-1", "ver-1", "sync"));

        registrar.disableBySource(ModuleActionSourceType.WORKFLOW_DEFINITION, "def-1");

        assertThat(actionService.findByModuleAliasAndActionCode("sales.contract", "syncWorkflow").getEnabled())
                .isFalse();
    }

    private ModuleActionContribution contribution(String actionCode, String sourceId,
                                                  String versionId, String bindingAlias) {
        return new ModuleActionContribution(
                "sales.contract",
                null,
                actionCode,
                actionCode,
                "同步流程",
                EntityActionCategory.WORKFLOW,
                EntityActionLevel.RECORD,
                EntityActionAccessMode.AUTH_REQUIRED,
                true,
                false,
                ActionDefaultGrantPolicy.NONE,
                null,
                null,
                EntityActionExecutorType.SERVICE,
                "platform.workflow",
                bindingAlias == null ? ModuleActionSourceType.WORKFLOW_RUNTIME : ModuleActionSourceType.WORKFLOW_DEFINITION,
                sourceId,
                versionId,
                bindingAlias == null ? null : ModuleActionBindingType.WORKFLOW_DEFINITION,
                bindingAlias == null ? null : sourceId,
                bindingAlias,
                true
        );
    }

    private PlatformModule module(String alias) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setTitle(alias);
        return module;
    }
}
