package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only composer directory for list query summary declarations. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/platform.module/{moduleAlias}/page-query-summary-catalog")
public class PageQuerySummaryCatalogWebController {
    private final PageQuerySummaryCatalogService catalog;

    public PageQuerySummaryCatalogWebController(PageQuerySummaryCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @CustomActionEndpoint(value = "viewPageQuerySummaryCatalog", title = "查看页面汇总指标",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public PageQuerySummaryCatalog list(@PathVariable String moduleAlias) {
        return catalog.list(moduleAlias);
    }
}
