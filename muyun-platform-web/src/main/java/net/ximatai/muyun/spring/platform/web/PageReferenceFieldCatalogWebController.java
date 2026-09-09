package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Composer-only metadata directory; this endpoint never loads reference records. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/platform.module/{moduleAlias}/page-reference-fields")
public class PageReferenceFieldCatalogWebController {
    private final PageReferenceFieldCatalogService catalog;

    public PageReferenceFieldCatalogWebController(PageReferenceFieldCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @CustomActionEndpoint(value = "viewPageReferenceFields", title = "查看页面引用字段",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public PageReferenceFieldCatalog list(@PathVariable String moduleAlias,
                                          @RequestParam(required = false) String path) {
        return catalog.list(moduleAlias, path);
    }
}
