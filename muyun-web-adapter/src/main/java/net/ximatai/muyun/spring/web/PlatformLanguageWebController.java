package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.platform.AnonymousEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public deployment catalog; it contains no tenant or account facts. */
@RestController
public class PlatformLanguageWebController {
    private final LanguageCatalog catalog;
    public PlatformLanguageWebController(LanguageCatalog catalog) { this.catalog = catalog; }
    @AnonymousEndpoint
    @GetMapping("/platform.localization/catalog")
    public Catalog catalog() { return new Catalog(catalog.defaultTag(), catalog.languages()); }
    public record Catalog(String defaultTag, List<LanguageCatalog.Language> languages) { }
}
