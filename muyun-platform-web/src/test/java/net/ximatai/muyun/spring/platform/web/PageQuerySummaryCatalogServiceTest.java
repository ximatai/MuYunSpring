package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageQuerySummaryCatalogServiceTest {
    @Test
    void listsEligibleStaticFieldsAndRegisteredContributorsOnly() {
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.contract", "合同")
                .entities(List.of(new EntityDefinition("contract", "contract", "合同", List.of(
                        FieldDefinition.string("title", "合同名称"), FieldDefinition.decimal("amount", "金额"),
                        FieldDefinition.decimal("derivedAmount", "派生金额").virtual())))).build();
        ListQuerySummaryContributor contributor = new ListQuerySummaryContributor() {
            @Override public String moduleAlias() { return "sales.contract"; }
            @Override public String contributorKey() { return "contract.pending-count"; }
            @Override public String title() { return "待处理合同"; }
            @Override public net.ximatai.muyun.spring.web.WebListQuerySummaryItem summarize(ListQuerySummaryContext context) { return null; }
        };
        PageQuerySummaryCatalog catalog = new PageQuerySummaryCatalogService(
                new StaticModuleDefinitionCatalog(List.of(definition)), absent(),
                new ListQuerySummaryContributorCatalog(List.of(contributor))).list("sales.contract");

        assertThat(catalog.fields()).containsExactly(new PageQuerySummaryCatalog.Field("amount", "金额"));
        assertThat(catalog.contributors()).containsExactly(
                new PageQuerySummaryCatalog.Contributor("contract.pending-count", "待处理合同"));
    }

    @Test
    void listsOnlySingleOptionAndOneReferenceGroupFieldsForStaticAndDynamicMainEntities() {
        StaticModuleDefinition staticDefinition = StaticModuleDefinition.builder("sales", "sales.static", "静态")
                .modelClass(StaticGroupModel.class)
                .entities(List.of(new EntityDefinition("static_root", "static_root", "静态", List.of(
                        FieldDefinition.string("status", "状态"), FieldDefinition.string("statuses", "多状态")))))
                .build();
        PageQuerySummaryCatalog staticCatalog = new PageQuerySummaryCatalogService(
                new StaticModuleDefinitionCatalog(List.of(staticDefinition)), absent(),
                new ListQuerySummaryContributorCatalog(List.of())).list("sales.static");
        assertThat(staticCatalog.groupFields()).containsExactly(
                new PageQuerySummaryCatalog.GroupField("status", "状态", ListQuerySummaryGroupFieldCatalog.Kind.OPTION));

        EntityDefinition child = new EntityDefinition("purchase_line", "purchase_line", "明细", List.of(
                FieldDefinition.string("lineStatus", "明细状态").dictionary("sales", "line_status")));
        EntityDefinition main = new EntityDefinition("purchase_root", "purchase_root", "采购", List.of(
                FieldDefinition.string("status", "状态").dictionary("sales", "purchase_status"),
                FieldDefinition.string("statuses", "多状态").dictionary("sales", "purchase_status", OptionSelectionMode.MULTIPLE),
                FieldDefinition.string("supplierId", "供应商"), FieldDefinition.string("tagIds", "标签").virtual()));
        ModuleDefinition dynamic = ModuleDefinition.builder("sales.purchase", "采购").entities(List.of(child, main))
                .mainEntityAlias("purchase_root")
                .references(List.of(EntityReferenceDefinition.to("purchase_root", "supplierId", ReferenceTarget.of("supply", "supplier")),
                        EntityReferenceDefinition.to("purchase_root", "tagIds", ReferenceTarget.of("supply", "tag")).many()))
                .build();
        DynamicRecordService records = mock(DynamicRecordService.class);
        when(records.moduleDefinitions()).thenReturn(List.of(dynamic));
        PageQuerySummaryCatalog dynamicCatalog = new PageQuerySummaryCatalogService(
                new StaticModuleDefinitionCatalog(List.of()), provider(records),
                new ListQuerySummaryContributorCatalog(List.of())).list("sales.purchase");
        assertThat(dynamicCatalog.groupFields()).containsExactlyInAnyOrder(
                new PageQuerySummaryCatalog.GroupField("status", "状态", ListQuerySummaryGroupFieldCatalog.Kind.OPTION),
                new PageQuerySummaryCatalog.GroupField("supplierId", "供应商", ListQuerySummaryGroupFieldCatalog.Kind.REFERENCE));
    }

    static final class StaticGroupModel {
        @OptionField(type = OptionSourceType.ENUM, enumType = StaticState.class)
        String status;
        @OptionField(type = OptionSourceType.ENUM, enumType = StaticState.class, selectionMode = OptionSelectionMode.MULTIPLE)
        List<String> statuses;
    }

    enum StaticState implements CodeTitleEnum {
        OPEN("open", "开启");
        private final String code;
        private final String title;
        StaticState(String code, String title) { this.code = code; this.title = title; }
        @Override public String getCode() { return code; }
        @Override public String getTitle() { return title; }
    }

    private static <T> ObjectProvider<T> absent() {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return null; }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
            @Override public T getObject() { return null; }
        };
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public T getObject() { return value; }
        };
    }
}
