package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationConstructionFieldServiceTest {
    @Test void requiresFormalMetadataPublicationPermissionBeforeReadingPlanOrPreviewing() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var permissions = mock(ActionExecutionPolicyService.class);
        var previews = mock(MetadataModelChangeSetPreviewService.class);
        var publisher = mock(MetadataModelChangeSetApplyService.class);
        var service = new ApplicationConstructionFieldService(plans, mock(ApplicationConstructionFieldChangeDao.class),
                mock(MetadataService.class), mock(ModuleMetadataRelationService.class), mock(MetadataFieldService.class),
                mock(FieldSpecService.class), previews, publisher, mock(DynamicRuntimeActivationService.class), permissions);
        doThrow(new PlatformAccessDeniedException("无字段发布权限")).when(permissions).requireAuthorized(argThat(context -> context.actionCode().equals("applyMetadataModelChangeSet")));
        try (var ignored = CurrentUserContext.use(CurrentUser.systemUser("restricted", "受限管理员"))) {
            assertThatThrownBy(() -> service.describe("plan", "order")).hasMessageContaining("无字段发布权限");
        }
        verifyNoInteractions(plans, previews, publisher);
    }
}
