package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ApplicationConstructionInitializationServiceTest {
    @Test void requiresConfigurationPermissionBeforeReadingHistoricalReceipts() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var receipts = mock(ApplicationConstructionInitializationDao.class);
        var actions = new java.util.ArrayList<String>();
        ActionExecutionPolicyService permissions = context -> {
            actions.add(context.permissionCode());
            if (context.moduleAlias().equals("platform.module_metadata_relation"))
                throw new PlatformAccessDeniedException("主实体创建权限已撤销");
        };
        var service = new ApplicationConstructionInitializationService(plans, receipts,
                mock(DynamicRuntimeActivationService.class), permissions);
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("operator", "Operator"))) {
            assertThatThrownBy(() -> service.status("plan", "entry")).hasMessageContaining("权限已撤销");
            assertThat(actions).anyMatch(code -> code.contains("platform.module_metadata_relation") && code.contains("createMainMetadata"));
            verifyNoInteractions(plans, receipts);
        }
    }

    @Test void checksPlanOwnershipBeforeLookingUpHistoricalReceipts() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var receipts = mock(ApplicationConstructionInitializationDao.class);
        var activation = mock(DynamicRuntimeActivationService.class);
        when(plans.read("plan")).thenThrow(new PlatformAccessDeniedException("无权读取方案"));
        var service = new ApplicationConstructionInitializationService(plans, receipts, activation, context -> {});
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("operator", "Operator"))) {
            assertThatThrownBy(() -> service.status("plan", "entry")).hasMessageContaining("无权读取方案");
            verifyNoInteractions(receipts, activation);
        }
    }

    @Test void readsLegacyReceiptAndRuntimeWithoutRequiringItForNewPlans() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var receipts = mock(ApplicationConstructionInitializationDao.class);
        var activation = mock(DynamicRuntimeActivationService.class);
        var service = new ApplicationConstructionInitializationService(plans, receipts, activation, context -> {});
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("operator", "Operator"))) {
            assertThat(service.status("plan", "entry")).isNull();
            verifyNoInteractions(activation);
            var receipt = new ApplicationConstructionInitialization();
            receipt.setObjectKey("entry"); receipt.setPlanRevision(1); receipt.setModuleAlias("legacy.entry");
            receipt.setMetadataId("metadata"); receipt.setRelationId("relation"); receipt.setRequestId("request");
            when(receipts.findById(anyString())).thenReturn(receipt);
            var result = service.status("plan", "entry");
            assertThat(result.receipt()).isEqualTo(new ApplicationConstructionPlanService.Initialization(
                    "entry", 1, "legacy.entry", "metadata", "relation", "request"));
            verify(activation).status("legacy.entry");
            verify(receipts, times(2)).findById(anyString());
            verifyNoMoreInteractions(receipts);
        }
    }
}
