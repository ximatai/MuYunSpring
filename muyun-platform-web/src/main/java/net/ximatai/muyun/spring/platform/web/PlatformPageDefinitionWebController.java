package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.web.NestedEnabledSortableCrudWebSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Module-scoped management API for page definitions; it intentionally has no UI-set semantics. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = PlatformPageDefinitionService.MODULE_ALIAS, title = "平台页面定义")
@RequestMapping("/platform.module/{moduleAlias}/pages")
public class PlatformPageDefinitionWebController
        extends NestedEnabledSortableCrudWebSupport<PlatformPageDefinition, PlatformPageDefinitionService> {

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        criteria.eq("moduleAlias", pathVariable(request, "moduleAlias"));
    }

    @Override
    protected void bindScope(PlatformPageDefinition record, HttpServletRequest request) {
        record.setModuleAlias(pathVariable(request, "moduleAlias"));
    }

    @Override
    protected boolean inScope(PlatformPageDefinition record, HttpServletRequest request) {
        return Objects.equals(record.getModuleAlias(), pathVariable(request, "moduleAlias"));
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "page definition does not belong to module: " + pathVariable(request, "moduleAlias") + "." + id;
    }
}
