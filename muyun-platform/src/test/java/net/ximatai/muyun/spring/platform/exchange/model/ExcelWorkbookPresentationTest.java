package net.ximatai.muyun.spring.platform.exchange.model;

import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import net.ximatai.muyun.spring.platform.exchange.template.DynamicExchangeTemplatePlanBuilder;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import net.ximatai.muyun.spring.platform.exchange.reader.ExcelWorkbookParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ExcelWorkbookPresentationTest {
    @Test void localizesColumnLabelsWhilePreservingWorkbookIdentityAndRawBusinessValues() {
        var descriptor = DynamicModuleDescriptor.from(new ModuleDefinition("sales.order", "订单",
                List.of(new EntityDefinition("order", "orders", "订单", List.of(FieldDefinition.string("title", "订单名称"))))));
        var original = new DynamicExchangeTemplatePlanBuilder().build(descriptor);
        var sheet = original.sheets().getFirst();
        var plan = new ExcelWorkbookPlan(original.meta(), List.of(new ExcelSheetPlan(sheet.sheetName(), sheet.entityAlias(), true,
                sheet.columns(), List.of(List.of("id-1", "张三的订单")))));
        var source = ConfigurationTextSources.metadataFieldTitle("sales.order", "order", "title", "订单名称");
        var beans = new DefaultListableBeanFactory();
        var code = TextResources.load(getClass().getClassLoader(), LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"));
        beans.registerSingleton("texts", (TextResolver) (reference, args, tag) -> reference.equals(source) && "en".equals(tag)
                ? new TextResources.Resolution("Order name", tag, tag, false, false) : code.resolve(reference, args, tag));
        ExcelWorkbookPlan localized;
        try (var language = LanguageContext.use("en")) {
            localized = new ExcelWorkbookPresentation(beans.getBeanProvider(TextResolver.class)).project(plan, descriptor);
        }
        assertThat(localized.meta()).isEqualTo(plan.meta());
        assertThat(localized.sheets().getFirst().sheetName()).isEqualTo("订单");
        assertThat(localized.sheets().getFirst().columns().getFirst().fieldName()).isEqualTo(ExcelExchangeProtocol.RELATE_ID_FIELD);
        assertThat(localized.sheets().getFirst().columns().getFirst().title()).isEqualTo("Relation ID");
        assertThat(localized.sheets().getFirst().columns().get(1).title()).isEqualTo("Order name");
        assertThat(localized.sheets().getFirst().columns().get(1).fieldName()).isEqualTo("title");
        assertThat(localized.sheets().getFirst().rows()).isEqualTo(plan.sheets().getFirst().rows());
        assertThat(plan.sheets().getFirst().columns().get(1).title()).isEqualTo("订单名称");
        byte[] workbook = new ExcelWorkbookPlanWriter().writeToBytes(localized);
        var reparsed = new ExcelWorkbookParser().parse(workbook);
        assertThat(reparsed.meta().moduleAlias()).isEqualTo("sales.order");
        assertThat(reparsed.sheets().getFirst().columns()).extracting(ParsedColumn::fieldName).containsExactly("relateId", "title");
        assertThat(reparsed.sheets().getFirst().rows()).containsExactly(List.of("id-1", "张三的订单"));
    }

    @Test void doesNotClaimUserColumnTitlesOrBusinessFieldsNamedLikeErrorDiagnostics() {
        var descriptor = DynamicModuleDescriptor.from(new ModuleDefinition("sales.order", "订单", List.of(
                new EntityDefinition("order", "orders", "订单", List.of(FieldDefinition.string("errorReason", "错误原因"))))));
        var original = new DynamicExchangeTemplatePlanBuilder().build(descriptor);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("texts", TextResources.load(getClass().getClassLoader(), LanguageCatalog.load(getClass().getClassLoader(), "zh-CN")));
        try (var language = LanguageContext.use("en")) {
            var localized = new ExcelWorkbookPresentation(beans.getBeanProvider(TextResolver.class)).project(original, descriptor);
            assertThat(localized.sheets().getFirst().columns().get(1).title()).isEqualTo("错误原因");
            assertThat(localized.sheets().getFirst().columns().get(1).titleSource().sourceKind()).isEqualTo(TextReference.SourceKind.CONFIG);
            var sheet = original.sheets().getFirst();
            var custom = new ExcelWorkbookPlan(original.meta(), List.of(new ExcelSheetPlan(sheet.sheetName(), "order", true,
                    List.of(new ExcelColumnPlan("errorReason", "Custom 中文 {title}")), List.of())));
            assertThat(new ExcelWorkbookPresentation(beans.getBeanProvider(TextResolver.class)).project(custom, descriptor)
                    .sheets().getFirst().columns().getFirst().title()).isEqualTo("Custom 中文 {title}");
        }
    }
}
