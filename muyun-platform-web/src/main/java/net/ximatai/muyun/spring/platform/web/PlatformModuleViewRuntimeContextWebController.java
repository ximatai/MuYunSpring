package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime descriptor transport for opening record details independently of a menu entry. */
@RestController
@RequestMapping("/platform.module/{moduleAlias}/view-context")
public class PlatformModuleViewRuntimeContextWebController {
    private final PlatformModuleRuntimeContextService contextService;

    public PlatformModuleViewRuntimeContextWebController(PlatformModuleRuntimeContextService contextService) {
        this.contextService = contextService;
    }

    @GetMapping
    @ActionEndpoint(PlatformAction.VIEW)
    public PlatformModuleRuntimeContext context(@PathVariable String moduleAlias) {
        return contextService.context(moduleAlias);
    }
}
