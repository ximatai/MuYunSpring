package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.platform.ui.PageCompositionSaveService;
import net.ximatai.muyun.spring.platform.ui.PageCompositionDraftCompiler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PageCompositionSaveWebControllerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void catalogReflectsChildPermissionWithoutHidingFieldComponents(boolean allowed) {
        var service = mock(PageCompositionSaveService.class);
        when(service.catalog("revision")).thenReturn(new PageCompositionSaveService.ComponentCatalog(
                "main", 2, PageCompositionDraftCompiler.components(), false));
        ActionExecutionPolicyService policy = context -> {
            if ("createChildMetadata".equals(context.actionCode()) && !allowed)
                throw new PlatformAccessDeniedException("child denied");
        };
        var controller = new PageCompositionSaveWebController(service, new ActionEndpointContextResolver(), policy);
        var catalog = controller.catalog("revision");
        assertThat(catalog.canCreateChild()).isEqualTo(allowed);
        assertThat(catalog.components()).hasSize(5);
    }
}
