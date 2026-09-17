package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/platform.module/{moduleAlias}/context")
public class PlatformModuleRuntimeContextWebController {
    private final PlatformModuleRuntimeContextService contextService;

    public PlatformModuleRuntimeContextWebController(PlatformModuleRuntimeContextService contextService) {
        this.contextService = contextService;
    }

    @GetMapping
    @ActionEndpoint(PlatformAction.MENU)
    @ModuleDiscoveryEndpoint
    public PlatformModuleRuntimeContext context(@PathVariable String moduleAlias) {
        return contextService.context(moduleAlias);
    }
}
