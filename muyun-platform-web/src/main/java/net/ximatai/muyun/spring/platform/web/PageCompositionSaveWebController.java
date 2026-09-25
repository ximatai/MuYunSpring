package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand;
import net.ximatai.muyun.spring.platform.ui.PageCompositionSaveService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;

@RestController
@PlatformStaticWebProjection(module = "platform.presentation_publish")
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/platform.presentation_publish/revisions")
public class PageCompositionSaveWebController extends WebSupport<PageCompositionSaveService>
        implements SystemScope<PageCompositionSaveService> {
    private final ActionEndpointContextResolver actions;
    private final ActionExecutionPolicyService policy;

    public PageCompositionSaveWebController(PageCompositionSaveService service,
                                            ActionEndpointContextResolver actions, ActionExecutionPolicyService policy) {
        this.service = service;
        this.actions = actions;
        this.policy = policy;
    }

    @GetMapping("/{id}/component-catalog")
    @CustomActionEndpoint(value = "publish", title = "页面组件库", level = PlatformActionLevel.RECORD)
    public PageCompositionSaveService.ComponentCatalog catalog(@PathVariable String id) {
        policy.requireAuthorized(actions.resolveActionCode(ModuleMetadataRelationService.MODULE_ALIAS,
                "applyMetadataModelChangeSet", Set.of()));
        return webScope(() -> {
            var catalog = service().catalog(id);
            boolean canCreateChild;
            try {
                policy.requireAuthorized(actions.resolveActionCode(ModuleMetadataRelationService.MODULE_ALIAS,
                        "createChildMetadata", Set.of()));
                canCreateChild = true;
            } catch (PlatformAccessDeniedException denied) {
                canCreateChild = false;
            }
            return catalog.withChildCreation(canCreateChild);
        });
    }

    @PostMapping("/{id}/save-composition")
    @CustomActionEndpoint(value = "publish", title = "保存页面", level = PlatformActionLevel.RECORD)
    public PlatformPresentationRevision save(@PathVariable String id, @RequestBody PageCompositionSaveCommand command) {
        policy.requireAuthorized(actions.resolveActionCode(ModuleMetadataRelationService.MODULE_ALIAS,
                "applyMetadataModelChangeSet", Set.of()));
        if (!command.newChildren().isEmpty()) policy.requireAuthorized(actions.resolveActionCode(
                ModuleMetadataRelationService.MODULE_ALIAS, "createChildMetadata", Set.of()));
        return webScope(() -> service().save(id, command));
    }
}
