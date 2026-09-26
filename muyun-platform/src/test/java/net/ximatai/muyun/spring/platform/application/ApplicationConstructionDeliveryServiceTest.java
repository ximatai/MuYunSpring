package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.platform.menu.*;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import net.ximatai.muyun.spring.platform.ui.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationConstructionDeliveryServiceTest {
    @Test void formalPageAndMenuPermissionsAreRequiredBeforeAnyPublication() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var permissions = mock(ActionExecutionPolicyService.class);
        var publisher = mock(PlatformPresentationRevisionPublishService.class);
        var menus = mock(MenuService.class);
        var service = new ApplicationConstructionDeliveryService(mock(IDatabaseOperations.class), plans,
                mock(ApplicationConstructionFieldService.class), mock(ApplicationConstructionDeliveryDao.class),
                mock(ApplicationConstructionAcceptanceDao.class), mock(PlatformPageDefinitionService.class),
                mock(PlatformPresentationVariantService.class), mock(PlatformPresentationRevisionService.class), publisher,
                mock(PlatformPresentationTemplateCatalog.class), mock(ApplicationConstructionPageCompiler.class), menus,
                mock(MenuSchemeService.class), mock(DynamicRuntimeActivationService.class), permissions);
        doThrow(new PlatformAccessDeniedException("无发布权限")).when(permissions).requireAuthorized(argThat(context ->
                context.actionCode().equals("publish") || context.moduleAlias().equals("platform.menu") && context.actionCode().equals("create")));
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("restricted", "受限管理员"))) {
            assertThatThrownBy(() -> service.preview("plan", new ApplicationConstructionDeliveryService.Proposal(1, "order", ApplicationConstructionDeliveryService.Kind.PAGE, "订单", List.of("number"), List.of("number"), List.of())))
                    .hasMessageContaining("无发布权限");
            assertThatThrownBy(() -> service.preview("plan", new ApplicationConstructionDeliveryService.Proposal(1, "order", ApplicationConstructionDeliveryService.Kind.ENTRY, "订单", List.of(), List.of(), List.of())))
                    .hasMessageContaining("无发布权限");
        }
        verifyNoInteractions(plans, publisher, menus);
    }
}
