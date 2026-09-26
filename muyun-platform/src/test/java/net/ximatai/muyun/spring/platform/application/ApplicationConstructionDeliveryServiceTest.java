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
    @Test void choicesFollowEvidenceWithoutForcingOptionalWritesOrSerializingIndependentObjects() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var publisher = mock(PlatformPresentationRevisionPublishService.class);
        var menus = mock(MenuService.class);
        var service = spy(new ApplicationConstructionDeliveryService(mock(IDatabaseOperations.class), plans,
                mock(ApplicationConstructionFieldService.class), mock(ApplicationConstructionDeliveryDao.class),
                mock(ApplicationConstructionAcceptanceDao.class), mock(PlatformPageDefinitionService.class),
                mock(PlatformPresentationVariantService.class), mock(PlatformPresentationRevisionService.class), publisher,
                mock(PlatformPresentationTemplateCatalog.class), mock(ApplicationConstructionPageCompiler.class), menus,
                mock(MenuSchemeService.class), mock(DynamicRuntimeActivationService.class), mock(ActionExecutionPolicyService.class)));
        var content = new ApplicationConstructionPlanContent("登记", "登记两个独立对象", List.of(), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("first", "对象一", "登记"),
                        new ApplicationConstructionPlanContent.BusinessObject("second", "对象二", "登记")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(plans.read("plan")).thenReturn(new ApplicationConstructionPlanService.Snapshot("plan", 1, content,
                java.time.Instant.EPOCH, "INITIALIZED", List.of(
                new ApplicationConstructionPlanService.Initialization("first", 1, "sample.first", "m", "r", "request")), List.of(), List.of()));
        doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "ACTIVE", false,
                false, null, false, false, List.of(), List.of(), List.of())).when(service).progress("plan", "first");
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("admin", "管理员"))) {
            var task = service.task("plan");
            assertThat(task.objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactlyInAnyOrder(ApplicationConstructionDeliveryService.TaskAction.CONFIGURE_FIELDS,
                            ApplicationConstructionDeliveryService.TaskAction.PUBLISH_PAGE);
            assertThat(task.objects().get(1).options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.INITIALIZE);
            doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "ACTIVE", true,
                    true, "menu", false, false, List.of(), List.of(), List.of())).when(service).progress("plan", "first");
            assertThat(service.task("plan").objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .contains(ApplicationConstructionDeliveryService.TaskAction.VERIFY_BUSINESS)
                    .doesNotContain(ApplicationConstructionDeliveryService.TaskAction.CREATE_ENTRY);
            doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "PENDING", false,
                    false, null, false, false, List.of(), List.of(), List.of())).when(service).progress("plan", "first");
            assertThat(service.task("plan").objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.VERIFY_RUNTIME);
        }
        verifyNoInteractions(publisher, menus);
    }

}
