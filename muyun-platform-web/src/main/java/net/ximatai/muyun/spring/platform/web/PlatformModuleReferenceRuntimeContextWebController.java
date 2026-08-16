package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime descriptor transport for a module consumed as a navigator reference source. */
@RestController
@RequestMapping("/platform.module/{moduleAlias}/reference-context")
public class PlatformModuleReferenceRuntimeContextWebController {
    private final PlatformModuleRuntimeContextService contextService;

    public PlatformModuleReferenceRuntimeContextWebController(PlatformModuleRuntimeContextService contextService) {
        this.contextService = contextService;
    }

    @GetMapping
    @ActionEndpoint(PlatformAction.REFERENCE)
    public PlatformModuleRuntimeContext context(@PathVariable String moduleAlias) {
        return contextService.context(moduleAlias);
    }
}
