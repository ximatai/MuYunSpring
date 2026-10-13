package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.LanguageContext;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ModulePresentationResponseAdviceTest {
    @Test
    void includesQueryControlsAndSummaryTitlesWithoutTranslatingProtocolFields() {
        var ui = ModuleUiDescriptorCompiler.compile(ModuleUiDefinition.builder("test.order")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("code")))
                        .detail(detail -> detail.editor(form -> form.field("code")))))
                .build());
        var page = ui.page();
        var list = new ResolvedPageListDescriptor(null, page.list().fields(), null, null, List.of(),
                List.of(new ResolvedPageListFieldPersistentQueryControlDescriptor("active", "仅启用", "enabled",
                        net.ximatai.muyun.spring.ability.query.QueryOperator.EQ, List.of(true))),
                List.of(new ResolvedPageListQuerySummaryDescriptor("total", "合计", PageListQuerySummaryDefinition.Source.SUM,
                        "amount", null, "category", "分类", "金额")));
        ui = ui.withPage(new ResolvedModulePageDescriptor(page.template(), page.explorer(), page.navigator(), list,
                page.treeResource(), page.detail(), page.traits(), page.quickSearchFields(), page.actions()));
        var sources = ModuleUiTextLocations.codeSources(ui);
        assertThat(sources).containsKeys("ui/test.order/page/list/persistentQueryControls/active/title",
                "ui/test.order/page/list/querySummaries/total/title", "ui/test.order/page/list/querySummaries/total/groupByTitle",
                "ui/test.order/page/list/querySummaries/total/sumFieldTitle");
        assertThat(sources.keySet()).noneMatch(key -> key.endsWith("/fieldName") || key.endsWith("/groupByField"));
    }

    @Test
    void projectsOnlyDeclaredTextAndLeavesSharedExecutionFactsIntact() {
        var ui = ModuleUiDescriptorCompiler.compile(ModuleUiDefinition.builder("test.order")
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("订单列表"))
                        .detail(detail -> detail.editor(form -> form.field("title", field -> field.label("订单名称"))))))
                .build(), ModuleKind.STATIC, "订单");
        var compiledSources = new java.util.LinkedHashMap<>(ModuleUiTextLocations.codeSources(ui));
        String hiddenKey = "ui/test.order/removed/navigator/title";
        compiledSources.put(hiddenKey, net.ximatai.muyun.spring.common.i18n.TextReference.code(hiddenKey, "未获授权的导航标题"));
        ui = ui.withSourceTexts(compiledSources);
        String key = "ui/test.order/page/detail/editor/fields/main/title/label";
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var texts = new TextResources(catalog, Map.of("en", Map.of(key, "Order name")));
        var mapper = new ObjectMapper();
        var advice = new ModulePresentationResponseAdvice(mapper, texts, catalog);
        var context = new PlatformModuleRuntimeContext("test.order", "在线模块标题", ModuleKind.STATIC,
                ModuleEntryType.MODULE, null, null, null, Set.of(), List.of(), Set.of(), List.of(), ui, false);
        try (var ignored = LanguageContext.use("en")) {
            var output = mapper.valueToTree(advice.project(context));
            assertThat(output.at("/uiDescriptor/page/detail/editor/fields/0/label").asText()).isEqualTo("Order name");
            assertThat(output.at("/uiDescriptor/page/detail/editor/fields/0/fieldRef/fieldName").asText()).isEqualTo("title");
            assertThat(output.at("/title").asText()).isEqualTo("在线模块标题");
            var source = output.at("/textBindings/~1uiDescriptor~1page~1detail~1editor~1fields~10~1label/source");
            assertThat(source.get("originalText").asText()).isEqualTo("订单名称");
            assertThat(source.get("sourceId").asText()).isEqualTo(key);
            assertThat(output.toString()).doesNotContain("未获授权的导航标题");
        }
        assertThat(ui.page().detail().editor().fields().getFirst().label()).isEqualTo("订单名称");
        assertThat(ui.withPage(ui.page()).sourceTexts()).isEqualTo(ui.sourceTexts());
        assertThat(ui.sourceTexts()).containsKey(hiddenKey);
    }
}
