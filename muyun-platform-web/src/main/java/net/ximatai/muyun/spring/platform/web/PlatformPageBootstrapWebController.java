package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrap;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrapService;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Delivers one permission-scoped page entry for either a static or dynamic module.
 */
@RestController
@RequestMapping("/platform.menu")
public class PlatformPageBootstrapWebController {
    private final PlatformPageBootstrapService bootstrapService;
    private final PlatformModuleRuntimeContextService runtimeContextService;
    private final ActiveTenantVerifier activeTenantVerifier;

    public PlatformPageBootstrapWebController(PlatformPageBootstrapService bootstrapService,
                                              PlatformModuleRuntimeContextService runtimeContextService,
                                              ActiveTenantVerifier activeTenantVerifier) {
        this.bootstrapService = bootstrapService;
        this.runtimeContextService = runtimeContextService;
        this.activeTenantVerifier = activeTenantVerifier;
    }

    @GetMapping("/{menuId}/entry")
    public PlatformPageBootstrapResponse entry(@PathVariable String menuId,
                                               @RequestParam(defaultValue = "WEB") PlatformUiClientType clientType) {
        requireTenantContext();
        PlatformPageBootstrap bootstrap = bootstrapService.bootstrapByMenu(menuId, clientType);
        PlatformModuleRuntimeContext runtimeContext = runtimeContextService.context(bootstrap.entry().moduleAlias());
        return new PlatformPageBootstrapResponse(
                bootstrap.entry(),
                bootstrap.clientType(),
                runtimeContext.mainEntityAlias(),
                permissionScopedResolvedConfig(bootstrap.resolvedConfig(), runtimeContext),
                "/" + bootstrap.entry().moduleAlias() + "/openapi"
        );
    }

    private PlatformResolvedPageConfig permissionScopedResolvedConfig(PlatformResolvedPageConfig config,
                                                                       PlatformModuleRuntimeContext runtimeContext) {
        Set<String> visibleActionCodes = runtimeContext.actions().stream()
                .filter(PlatformModuleRuntimeAction::authorized)
                .map(PlatformModuleRuntimeAction::actionCode)
                .collect(java.util.stream.Collectors.toSet());
        return new PlatformResolvedPageConfig(
                config.uiFields(),
                config.queryItems(),
                config.fieldUiControls(),
                config.associationBlocks(),
                config.actionBlocks().stream()
                        .filter(block -> visibleActionCodes.contains(block.actionCode()))
                        .toList(),
                config.taskBlocks()
        );
    }

    private void requireTenantContext() {
        if (TenantContext.isSystem()) {
            return;
        }
        String tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException("page bootstrap requires tenant context"));
        activeTenantVerifier.verifyActiveTenant(tenantId);
    }
}
