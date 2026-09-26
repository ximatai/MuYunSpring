package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ApplicationConstructionInitializationServiceTest {
    @Test void reusesTheRegisteredMetadataRelationActionPermissionBeforeReadingOrWritingConfiguration() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var modules = mock(PlatformModuleService.class);
        var orchestration = mock(ModuleMetadataOrchestrationService.class);
        var actions = new java.util.ArrayList<String>();
        ActionExecutionPolicyService permissions = context -> {
            actions.add(context.permissionCode());
            if (context.moduleAlias().equals("platform.module_metadata_relation"))
                throw new PlatformAccessDeniedException("主实体创建权限已撤销");
        };
        var service = new ApplicationConstructionInitializationService(plans, mock(ApplicationConstructionInitializationDao.class),
                mock(ApplicationService.class), modules, orchestration, mock(MetadataService.class),
                mock(DynamicRuntimeActivationService.class), permissions, mock(IDatabaseOperations.class));
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("operator", "Operator"))) {
            assertThatThrownBy(() -> service.preview("plan", null)).hasMessageContaining("权限已撤销");
            assertThat(actions).anyMatch(code -> code.contains("platform.module_metadata_relation") && code.contains("createMainMetadata"));
            verifyNoInteractions(plans, modules, orchestration);
        }
    }
    @Test void rejectsUnimplementedRelationshipsBeforeCreatingAnEmptyApplication() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var plan = mock(ApplicationConstructionPlanService.Snapshot.class);
        var content = mock(ApplicationConstructionPlanContent.class);
        when(plans.read("plan")).thenReturn(plan);
        when(plan.revision()).thenReturn(1);
        when(plan.content()).thenReturn(content);
        when(content.relationships()).thenReturn(java.util.List.of("一张订单有多条商品明细"));
        var applications = mock(ApplicationService.class);
        var modules = mock(PlatformModuleService.class);
        var orchestration = mock(ModuleMetadataOrchestrationService.class);
        var service = new ApplicationConstructionInitializationService(plans, mock(ApplicationConstructionInitializationDao.class),
                applications, modules, orchestration, mock(MetadataService.class), mock(DynamicRuntimeActivationService.class),
                context -> {}, mock(IDatabaseOperations.class));
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("operator", "Operator"))) {
            assertThatThrownBy(() -> service.preview("plan", new ApplicationConstructionInitializationService.Proposal(1, "order", "trial", "试用", "order")))
                    .hasMessageContaining("先商定并确认分期范围");
            verifyNoInteractions(applications, modules, orchestration);
        }
    }
}
