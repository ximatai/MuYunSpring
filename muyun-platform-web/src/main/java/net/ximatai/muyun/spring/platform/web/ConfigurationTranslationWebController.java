package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.localization.*;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = ConfigurationTranslationService.MODULE_ALIAS, title = "配置翻译", route = "/platform/translations")
@RequestMapping("/platform.localization")
public class ConfigurationTranslationWebController extends WebSupport<ConfigurationTranslationService>
        implements SystemScope<ConfigurationTranslationService> {
    public ConfigurationTranslationWebController(ConfigurationTranslationService service) { this.service = service; }
    @GetMapping("/modules/{moduleAlias}/sources")
    @CustomActionEndpoint(value = "viewTranslationSources", title = "查看配置原文", level = PlatformActionLevel.LIST)
    public List<TextReference> sources(@PathVariable String moduleAlias) { return webScope(() -> service.currentSources(moduleAlias)); }
    @GetMapping("/modules/{moduleAlias}/translations")
    @CustomActionEndpoint(value = "viewTranslations", title = "查看配置译文", level = PlatformActionLevel.LIST)
    public List<ConfigurationTranslation> translations(@PathVariable String moduleAlias) { return webScope(() -> service.translations(moduleAlias)); }
    @PostMapping("/drafts")
    @CustomActionEndpoint(value = "saveTranslationDraft", title = "保存翻译草稿", level = PlatformActionLevel.LIST)
    public ConfigurationTranslation save(@RequestBody Draft request) {
        return webScope(() -> service.saveDraft(request.moduleAlias(), request.sourceId(), request.sourceVersion(), request.languageTag(), request.text(), request.version()));
    }
    @PostMapping("/translations/{id}/publish")
    @CustomActionEndpoint(value = "publishTranslation", title = "发布配置译文", level = PlatformActionLevel.RECORD)
    public ConfigurationTranslation publish(@PathVariable String id, @RequestBody Publication request) {
        return webScope(() -> service.publish(id, request.sourceVersion(), request.version()));
    }
    public record Draft(String moduleAlias, String sourceId, String sourceVersion, String languageTag, String text, Integer version) { }
    public record Publication(String sourceVersion, Integer version) { }
}
