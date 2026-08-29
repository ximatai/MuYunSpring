package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariant;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;
import net.ximatai.muyun.spring.web.NestedEnabledSortableCrudWebSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Variants stay below their page definition so client, tenant and organization scope is visible. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = PlatformPresentationVariantService.MODULE_ALIAS, title = "平台页面呈现变体")
@RequestMapping("/platform.module/{moduleAlias}/pages/{pageId}/presentation-variants")
public class PlatformPresentationVariantWebController
        extends NestedEnabledSortableCrudWebSupport<PlatformPresentationVariant, PlatformPresentationVariantService> {
    private final PlatformPageDefinitionService pageService;

    public PlatformPresentationVariantWebController(PlatformPageDefinitionService pageService) {
        this.pageService = pageService;
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        requirePage(request);
        criteria.eq("pageId", pathVariable(request, "pageId"));
    }

    @Override
    protected void bindScope(PlatformPresentationVariant record, HttpServletRequest request) {
        requirePage(request);
        record.setPageId(pathVariable(request, "pageId"));
    }

    @Override
    protected boolean inScope(PlatformPresentationVariant record, HttpServletRequest request) {
        requirePage(request);
        return Objects.equals(record.getPageId(), pathVariable(request, "pageId"));
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "presentation variant does not belong to page: " + pathVariable(request, "pageId") + "." + id;
    }

    private PlatformPageDefinition requirePage(HttpServletRequest request) {
        PlatformPageDefinition page = pageService.requireVisiblePage(pathVariable(request, "pageId"));
        String moduleAlias = pathVariable(request, "moduleAlias");
        if (!Objects.equals(page.getModuleAlias(), moduleAlias)) {
            throw new IllegalArgumentException("page definition does not belong to module: " + moduleAlias + "." + page.getId());
        }
        return page;
    }
}
