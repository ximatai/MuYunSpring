package net.ximatai.muyun.spring.platform;

import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.code.CodeRule;
import net.ximatai.muyun.spring.platform.code.CodeRuleSegment;
import net.ximatai.muyun.spring.platform.code.CodeRuleSegmentService;
import net.ximatai.muyun.spring.platform.code.CodeRuleService;
import net.ximatai.muyun.spring.platform.code.CodeSequencePolicy;
import net.ximatai.muyun.spring.platform.code.CodeSequencePolicyService;
import net.ximatai.muyun.spring.platform.code.CodeValueMapping;
import net.ximatai.muyun.spring.platform.code.CodeValueMappingService;
import net.ximatai.muyun.spring.platform.currency.Currency;
import net.ximatai.muyun.spring.platform.currency.CurrencyService;
import net.ximatai.muyun.spring.platform.currency.ExchangeRate;
import net.ximatai.muyun.spring.platform.currency.ExchangeRateService;
import net.ximatai.muyun.spring.platform.currency.ExchangeRateType;
import net.ximatai.muyun.spring.platform.currency.ExchangeRateTypeService;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinition;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinitionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaticQuerySchemaContractTest {
    @Test
    void shouldExposeRepresentativeStaticQuerySchemasFromModelContracts() {
        QuerySchema application = new ApplicationService(new TestMemoryDao<Application>()).querySchema();
        assertField(application, "id", QueryValueType.STRING);
        assertField(application, "title", QueryValueType.STRING);
        assertField(application, "enabled", QueryValueType.BOOLEAN);
        assertField(application, "sortOrder", QueryValueType.INTEGER);
        assertThat(application.quickSearch().fields()).containsExactly("id", "title");

        CurrencyService currencyService = new CurrencyService(new TestMemoryDao<Currency>());
        QuerySchema currency = currencyService.querySchema();
        assertField(currency, "decimalScale", QueryValueType.INTEGER);
        assertField(currency, "roundingMode", QueryValueType.STRING);
        assertThat(currency.defaultSorts()).extracting(QuerySchema.DefaultSort::field)
                .containsExactly("sortOrder", "code");

        ExchangeRateTypeService rateTypeService = new ExchangeRateTypeService(new TestMemoryDao<ExchangeRateType>());
        QuerySchema exchangeRate = new ExchangeRateService(new TestMemoryDao<ExchangeRate>(), currencyService,
                rateTypeService).querySchema();
        assertField(exchangeRate, "effectiveDate", QueryValueType.DATE);
        assertField(exchangeRate, "rate", QueryValueType.DECIMAL);
        assertThat(exchangeRate.defaultSorts()).extracting(QuerySchema.DefaultSort::field)
                .containsExactly("effectiveDate", "fromCurrencyCode", "toCurrencyCode");
        assertThat(exchangeRate.defaultSorts().getFirst().desc()).isTrue();

        MetadataService metadataService = new MetadataService(new TestMemoryDao<Metadata>());
        FieldSpecService fieldTypeService = new FieldSpecService(new TestMemoryDao<FieldSpec>());
        QuerySchema metadataField = new MetadataFieldService(new TestMemoryDao<MetadataField>(), metadataService,
                fieldTypeService).querySchema();
        assertField(metadataField, "required", QueryValueType.BOOLEAN);
        assertField(metadataField, "uniqueField", QueryValueType.BOOLEAN);
        assertField(metadataField, "fieldName", QueryValueType.STRING);
        assertThat(metadataField.quickSearch().fields()).contains("fieldName", "columnName");

        QuerySchema menu = new MenuService(new TestMemoryDao<Menu>(),
                new MenuSchemeService(new TestMemoryDao<MenuScheme>()),
                new PlatformModuleService(new TestMemoryDao<PlatformModule>())).querySchema();
        assertField(menu, "pageMode", QueryValueType.STRING);
        assertField(menu, "enabled", QueryValueType.BOOLEAN);
        assertThat(menu.quickSearch().fields()).containsExactly("title");

        QuerySchema codeRule = codeRuleService().querySchema();
        assertField(codeRule, "globalDefault", QueryValueType.BOOLEAN);
        assertField(codeRule, "effectiveFrom", QueryValueType.DATETIME);
        assertField(codeRule, "effectiveTo", QueryValueType.DATETIME);

        QuerySchema workflowDefinition = new WorkflowDefinitionService(new TestMemoryDao<WorkflowDefinition>())
                .querySchema();
        assertField(workflowDefinition, "approvalEnabled", QueryValueType.BOOLEAN);
        assertField(workflowDefinition, "currentVersionNo", QueryValueType.INTEGER);
    }

    private CodeRuleService codeRuleService() {
        return new CodeRuleService(
                new TestMemoryDao<CodeRule>(),
                new CodeRuleSegmentService(new TestMemoryDao<CodeRuleSegment>()),
                new CodeSequencePolicyService(new TestMemoryDao<CodeSequencePolicy>()),
                new CodeValueMappingService(new TestMemoryDao<CodeValueMapping>())
        );
    }

    private void assertField(QuerySchema schema, String fieldName, QueryValueType valueType) {
        assertThat(field(schema, fieldName).valueType()).isEqualTo(valueType);
    }

    private QuerySchema.Field field(QuerySchema schema, String fieldName) {
        return schema.fields().stream()
                .filter(field -> field.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing query field: " + fieldName
                        + " in " + schema.fields().stream().map(QuerySchema.Field::name).toList()));
    }
}
