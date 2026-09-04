package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.DynamicModuleOverviewModeSaveCommand;
import net.ximatai.muyun.spring.platform.module.DynamicModuleOverviewModeService;
import net.ximatai.muyun.spring.platform.module.DynamicModuleOverviewModeSnapshot;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Module-scoped governance projection for a dynamic module's overview interaction pattern. */
@RestController
@PlatformStaticWebProjection(module = PlatformModuleService.MODULE_ALIAS)
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/platform.module/{moduleAlias}/overview-mode")
public class PlatformDynamicModuleOverviewModeWebController extends WebSupport<DynamicModuleOverviewModeService>
        implements SystemScope<DynamicModuleOverviewModeService> {
    public PlatformDynamicModuleOverviewModeWebController(DynamicModuleOverviewModeService service) { this.service = service; }
    @GetMapping
    @CustomActionEndpoint(value = "viewDynamicModuleOverviewMode", title = "查看动态模块概览模式", level = PlatformActionLevel.RECORD, recordIdPathVariable = "moduleAlias", dataAuth = false)
    public DynamicModuleOverviewModeSnapshot get(@PathVariable String moduleAlias) {
        return webScope(() -> service().get(PlatformNameRules.requireModuleAlias(moduleAlias)));
    }
    @PostMapping
    @CustomActionEndpoint(value = "saveDynamicModuleOverviewMode", title = "保存动态模块概览模式", level = PlatformActionLevel.RECORD, recordIdPathVariable = "moduleAlias", dataAuth = false)
    public DynamicModuleOverviewModeSnapshot save(@PathVariable String moduleAlias, @RequestBody DynamicModuleOverviewModeSaveCommand command) {
        return webScope(() -> service().save(PlatformNameRules.requireModuleAlias(moduleAlias), command));
    }
}
