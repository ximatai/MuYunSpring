package net.ximatai.muyun.spring.demo.quality.configuration;

import org.springframework.context.annotation.Profile;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.localization.ConfigurationTranslationService;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import net.ximatai.muyun.spring.platform.ui.*;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Publishes the normal management page through existing configuration services. */
@Profile("collaboration-demo")
@Component
public class QualityDemoPageTask implements PlatformBootstrapTask {
    private static final PageRequest ONE = PageRequest.of(1, 1);
    private final ModuleMetadataRelationService relations;
    private final PlatformPageDefinitionService pages;
    private final PlatformPresentationVariantService variants;
    private final PlatformPresentationRevisionService revisions;
    private final PlatformPresentationRevisionPublishService publication;
    private final ConfigurationTranslationService translations;

    public QualityDemoPageTask(ModuleMetadataRelationService relations, PlatformPageDefinitionService pages,
            PlatformPresentationVariantService variants, PlatformPresentationRevisionService revisions,
            PlatformPresentationRevisionPublishService publication, ConfigurationTranslationService translations) {
        this.relations = relations; this.pages = pages; this.variants = variants; this.revisions = revisions;
        this.publication = publication; this.translations = translations;
    }
    @Override public String name() { return "demo-quality-page"; }
    @Override public int order() { return 220; }
    @Override public void run() {
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("quality-demo-page", "Quality Demo Page"));
             var tenant = TenantContext.system("configure quality sample page")) {
            var page = pages.resolveGlobalPage(QualityDemoBootstrapTask.MODULE_ALIAS, "management").orElseGet(() -> {
                var main = relations.list(Criteria.of().eq("moduleAlias", QualityDemoBootstrapTask.MODULE_ALIAS)
                        .eq("relationRole", RelationRole.MAIN), ONE).stream().findFirst().orElseThrow();
                var created = new PlatformPageDefinition();
                created.setModuleAlias(QualityDemoBootstrapTask.MODULE_ALIAS); created.setAlias("management");
                created.setTitle("质量异常单"); created.setContractType(PlatformPageContractType.MANAGEMENT);
                created.setMainRelationId(main.getId());
                return pages.select(pages.insert(created));
            });
            var variant = variants.list(Criteria.of().eq("pageId", page.getId())
                    .eq("clientType", PlatformPresentationClientType.WEB).eq("scopeType", PlatformPresentationScopeType.GLOBAL)
                    .isNull("tenantId").isNull("organizationId"), ONE).stream().findFirst().orElse(null);
            if (variant == null) {
                variant = new PlatformPresentationVariant();
                variant.setPageId(page.getId()); variant.setClientType(PlatformPresentationClientType.WEB);
                variant.setScopeType(PlatformPresentationScopeType.GLOBAL); variant.setTitle("质量异常单 Web");
                variant = variants.select(variants.insert(variant));
            }
            var existing = revisions.list(Criteria.of().eq("variantId", variant.getId()));
            if (existing.stream().noneMatch(row -> row.getStatus() == PlatformPresentationRevisionStatus.PUBLISHED)) {
                var baseline = new PlatformPresentationRevision();
                baseline.setVariantId(variant.getId());
                baseline.setRevisionNo(existing.stream().mapToInt(PlatformPresentationRevision::getRevisionNo).max().orElse(0) + 1);
                baseline.setTitle("质量异常单管理页"); baseline.setStatus(PlatformPresentationRevisionStatus.DRAFT);
                baseline.setTemplateAlias(PlatformPresentationTemplateCatalog.MANAGEMENT_ALIAS);
                baseline.setTemplateVersion(PlatformPresentationTemplateCatalog.MANAGEMENT_VERSION);
                baseline.setUiTreeJson(baselineTree());
                publication.publish(revisions.insert(baseline));
            }
            // Only this sample's configuration labels are seeded. Business text never enters this store.
            var labels = Map.ofEntries(Map.entry("质量异常单", "Quality exception"), Map.entry("异常标题", "Title"),
                    Map.entry("异常说明", "Description"), Map.entry("商品", "Product"),
                    Map.entry("检测明细", "Inspections"), Map.entry("检测说明", "Inspection details"),
                    Map.entry("样品数量", "Sample quantity"), Map.entry("失效数量", "Failed quantity"),
                    Map.entry("团队调查记录", "Team investigations"), Map.entry("调查意见", "Investigation note"),
                    Map.entry("异常单列表", "Quality exceptions"), Map.entry("搜索异常标题", "Search exception title"));
            for (var source : translations.currentSources(QualityDemoBootstrapTask.MODULE_ALIAS)) {
                String english = labels.get(source.originalText());
                if (english != null) translations.seedPublished(QualityDemoBootstrapTask.MODULE_ALIAS, source, "en", english);
            }
        }
    }

    static String baselineTree() {
        return """
                {"template":"management","templateVersion":1,
                 "props":{"list":{"searchPlaceholder":"搜索异常标题"}},
                 "nodes":[
                  {"slot":"list","title":"异常单列表","fields":["title","productId","description"]},
                  {"slot":"form","title":"质量异常单","fields":["title","productId","description"],
                   "relations":[
                    {"relation":"inspections","title":"检测明细","fields":["description","sampleQuantity","failedQuantity"]},
                    {"relation":"investigations","title":"团队调查记录","fields":["description"]}
                   ]}
                 ]}
                """;
    }
}
