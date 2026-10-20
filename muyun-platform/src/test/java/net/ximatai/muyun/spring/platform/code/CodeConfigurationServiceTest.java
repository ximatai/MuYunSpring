package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.code.CodeTrigger;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeConfigurationServiceTest {
    @Test void exactTrialWireValuesMatchBusinessValuesIncludingMappingsAndNumericFormulas() {
        var sources = List.of(new CodeConfigurationService.Source("serial", "序号", "Long", null),
                new CodeConfigurationService.Source("amount", "金额", "DECIMAL", null),
                new CodeConfigurationService.Source("rate", "比率", "BigDecimal", null));
        doReturn(List.of(new CodeConfigurationService.Target("api.invoice", "invoice", "单据", "STATIC", null,
                List.of(), sources, "单据", null))).when(configuration).targets("api.invoice");
        var serial = segment(CodeSegmentType.FIELD_VALUE, "serial");
        var rule = rule(serial);
        var amount = segment(CodeSegmentType.FIELD_VALUE, "amount");
        var mapping = segment(CodeSegmentType.VALUE_MAPPING, "rate");
        var entry = new CodeValueMapping(); entry.setSourceValue("1.00"); entry.setTargetValue("ONE");
        mapping.setMappings(List.of(entry));
        var formula = segment(CodeSegmentType.FORMULA, null); formula.setFormulaExpr("{rate} + 1");
        rule.setSegments(List.of(serial, amount, mapping, formula));
        java.util.Map<String, Object> wire = java.util.Map.of("serial", "9007199254740993",
                "amount", "123456789012345678.12", "rate", "1.00");
        var decoded = configuration.previewContext(rule, wire);
        assertThat(decoded.get("serial")).isEqualTo(9007199254740993L);
        assertThat(decoded.get("amount")).isEqualTo(new java.math.BigDecimal("123456789012345678.12"));
        assertThat(decoded.get("rate")).isEqualTo(new java.math.BigDecimal("1.00"));
        var preview = new CodePreviewService();
        var browser = preview.previewDraft(new PreviewCodeRuleCommand(rule, decoded, null, null, null));
        var business = preview.previewDraft(new PreviewCodeRuleCommand(rule, java.util.Map.of(
                "serial", 9007199254740993L, "amount", new java.math.BigDecimal("123456789012345678.12"),
                "rate", new java.math.BigDecimal("1.00")), null, null, null));
        assertThat(browser).isEqualTo(business);
        assertThat(browser.value()).contains("9007199254740993", "123456789012345678.12", "ONE", "2.0");
        assertThat(wire.get("serial")).isInstanceOf(String.class);
        assertThatThrownBy(() -> configuration.previewContext(rule, java.util.Map.of("serial", "9223372036854775808")))
                .hasMessageContaining("Invalid numeric trial value");
        assertThatThrownBy(() -> configuration.previewContext(rule, java.util.Map.of("amount", "NaN")))
                .hasMessageContaining("Invalid numeric trial value");
    }

    @Test void configurationAvailabilityUsesDeclaredFieldsEvenWithoutRulesOrRuntimeActivation() {
        var modules = mock(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class);
        var service = spy(new CodeConfigurationService(null, modules, null, null, null, null, null,
                mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService.class),
                mock(net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService.class)));
        var module = new net.ximatai.muyun.spring.platform.module.PlatformModule();
        module.setAlias("demo.module");
        module.setEnabled(false);
        when(modules.resolveVisibleModule("demo.module")).thenReturn(module);
        doReturn(List.of()).when(service).targets("demo.module");
        assertThat(service.availability("demo.module")).isEqualTo(new CodeConfigurationService.Availability(false, "not-declared"));
        module.setModuleKind(net.ximatai.muyun.spring.platform.module.ModuleKind.DYNAMIC);
        assertThat(service.availability("demo.module")).isEqualTo(new CodeConfigurationService.Availability(false, "no-model"));
        var empty = new CodeConfigurationService.Target("demo.module", "main", "主表", "DYNAMIC", "relation",
                List.of(), List.of(), "模块", null);
        doReturn(List.of(empty)).when(service).targets("demo.module");
        assertThat(service.availability("demo.module")).isEqualTo(new CodeConfigurationService.Availability(false, "no-fields"));
        var field = new CodeConfigurationService.Field("documentNo", "编号", 64, "field", null, null, null, null);
        doReturn(List.of(new CodeConfigurationService.Target("demo.module", "main", "主表", "DYNAMIC", "relation",
                List.of(field), List.of(), "模块", null))).when(service).targets("demo.module");
        assertThat(service.availability("demo.module")).isEqualTo(new CodeConfigurationService.Availability(true, null));
        assertThat(service.availability("missing")).isEqualTo(new CodeConfigurationService.Availability(false, "unavailable"));
    }
    @Test void staticCatalogUsesTheBusinessModuleTitleWithoutRequiringDynamicMetadataOrAPage() {
        var catalog = mock(CodeTargetCatalog.class);
        var modules = mock(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class);
        var service = mock(net.ximatai.muyun.spring.ability.code.CodeAbility.class);
        when(service.getModuleAlias()).thenReturn("api.invoice"); when(service.codeEntityAlias()).thenReturn("invoice");
        doReturn(net.ximatai.muyun.spring.platform.code.fixture.StaticCodeRecord.class).when(service).modelClass();
        when(catalog.staticTargets()).thenReturn(List.of(service));
        var module = new net.ximatai.muyun.spring.platform.module.PlatformModule(); module.setAlias("api.invoice"); module.setTitle("业务单据");
        when(modules.resolveVisibleModule("api.invoice")).thenReturn(module);
        var configuration = new CodeConfigurationService(catalog, modules, null, null, null, null, null, mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService.class), mock(net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService.class));
        assertThat(configuration.targets("api.invoice")).singleElement().satisfies(target -> {
            assertThat(target.title()).isEqualTo("业务单据"); assertThat(target.entityAlias()).isEqualTo("invoice");
            assertThat(target.relationId()).isNull(); assertThat(target.moduleTitle()).isEqualTo("业务单据");
        });
        when(modules.resolveVisibleModule("api.invoice")).thenReturn(null);
        assertThat(configuration.targets("api.invoice")).singleElement()
                .extracting(CodeConfigurationService.Target::title).isEqualTo("Static business coding contract");
    }
    private final CodeConfigurationService configuration = spy(new CodeConfigurationService(null, null, null, null, null, null, null, mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService.class), mock(net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService.class)));
    private void target(CodeFieldRole role, CodeTrigger trigger) {
        doReturn(List.of(new CodeConfigurationService.Target("api.invoice", "invoice", "单据", "STATIC", null,
                List.of(new CodeConfigurationService.Field("documentNo", "编码", 32, null, null, null, role, trigger)),
                List.of(new CodeConfigurationService.Source("title", "名称", "STRING", 80),
                        new CodeConfigurationService.Source("documentNo", "编码", "STRING", 32)), "业务单据", null))).when(configuration).targets("api.invoice");
    }
    private CodeRule rule(CodeRuleSegment segment) {
        var rule = new CodeRule(); rule.setModuleAlias("api.invoice"); rule.setEntityAlias("invoice"); rule.setFieldName("documentNo");
        rule.setFieldRole(CodeFieldRole.NORMAL); rule.setSegments(List.of(segment)); return rule;
    }
    private CodeRuleSegment segment(CodeSegmentType type, String source) {
        var segment = new CodeRuleSegment(); segment.setSegmentType(type); segment.setSourceRef(source); return segment;
    }
    @Test void declarationOwnsTheRoleAndAllTargetIdentifiersMustAgree() {
        target(CodeFieldRole.PRIMARY, CodeTrigger.SAVE);
        var rule = rule(segment(CodeSegmentType.CONSTANT, null));
        configuration.validateTarget(rule); assertThat(rule.getFieldRole()).isEqualTo(CodeFieldRole.PRIMARY);
        rule.setMetadataFieldId("foreign-dynamic-field");
        assertThatThrownBy(() -> configuration.validateTarget(rule)).hasMessageContaining("identifiers disagree");
        target(null, null); rule.setMetadataFieldId(null);
        assertThatThrownBy(() -> configuration.validateTarget(rule)).hasMessageContaining("Declare the business code field");
    }
    @Test void compilerRejectsUnknownSourcesAndUnboundedOrOversizedOutputButAcceptsExplicitTruncation() {
        target(CodeFieldRole.NORMAL, CodeTrigger.SAVE);
        var segment = segment(CodeSegmentType.FIELD_VALUE, "missing"); var rule = rule(segment);
        assertThatThrownBy(() -> configuration.validateRule(rule, rule.getSegments())).hasMessageContaining("Unknown or unavailable");
        segment.setSourceRef("title");
        assertThatThrownBy(() -> configuration.validateRule(rule, rule.getSegments())).hasMessageContaining("exceeds the target field length");
        segment.setLength(12); segment.setTruncatePolicy(CodeTruncatePolicy.RIGHT);
        assertThatCode(() -> configuration.validateRule(rule, rule.getSegments())).doesNotThrowAnyException();
        segment.setSegmentType(CodeSegmentType.FORMULA); segment.setFormulaExpr("{title}"); segment.setTruncatePolicy(CodeTruncatePolicy.NONE);
        assertThatThrownBy(() -> configuration.validateRule(rule, rule.getSegments())).hasMessageContaining("unbounded code segment");
        segment.setTruncatePolicy(CodeTruncatePolicy.RIGHT);
        assertThatCode(() -> configuration.validateRule(rule, rule.getSegments())).doesNotThrowAnyException();
        segment.setFormulaExpr("{title} = 'changed'");
        assertThatThrownBy(() -> configuration.validateRule(rule, rule.getSegments())).hasMessageContaining("without assignments");
    }
    @Test void constantsRespectTruncationAndActionVariablesRequireExplicitTiming() {
        target(CodeFieldRole.NORMAL, CodeTrigger.EXPLICIT);
        var segment = segment(CodeSegmentType.CONTEXT_VAR, "externalYear"); segment.setLength(8); segment.setTruncatePolicy(CodeTruncatePolicy.RIGHT);
        var rule = rule(segment);
        assertThatCode(() -> configuration.validateRule(rule, rule.getSegments())).doesNotThrowAnyException();
        target(CodeFieldRole.NORMAL, CodeTrigger.SAVE);
        assertThatThrownBy(() -> configuration.validateRule(rule, rule.getSegments())).hasMessageContaining("Unknown or unavailable");
        segment.setSegmentType(CodeSegmentType.CONSTANT); segment.setFixedValue("a".repeat(100));
        assertThatCode(() -> configuration.validateRule(rule, rule.getSegments())).doesNotThrowAnyException();
    }
    @Test void candidateCatalogExcludesResolvedReferencesWithoutDroppingTheirValueAsASource() {
        var catalog = mock(CodeTargetCatalog.class);
        var modules = mock(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class);
        var relations = mock(net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService.class);
        var schemas = mock(net.ximatai.muyun.spring.platform.metadata.MetadataService.class);
        var fields = mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldService.class);
        var bindings = mock(net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService.class);
        var compiler = mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldDefinitionCompiler.class);
        var references = mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService.class);
        var module = new net.ximatai.muyun.spring.platform.module.PlatformModule(); module.setAlias("sample.order"); module.setModuleKind(net.ximatai.muyun.spring.platform.module.ModuleKind.DYNAMIC);
        var relation = new net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation(); relation.setId("relation"); relation.setMetadataId("schema");
        var schema = new net.ximatai.muyun.spring.platform.metadata.Metadata(); schema.setAlias("order");
        var product = new net.ximatai.muyun.spring.platform.metadata.MetadataField(); product.setId("product"); product.setFieldName("productId"); product.setTitle("商品");
        var number = new net.ximatai.muyun.spring.platform.metadata.MetadataField(); number.setId("number"); number.setFieldName("documentNo"); number.setTitle("单号");
        when(catalog.staticTargets()).thenReturn(List.of()); when(modules.resolveVisibleModule("sample.order")).thenReturn(module);
        when(relations.list(any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(List.of(relation));
        when(schemas.select("schema")).thenReturn(schema);
        when(fields.list(any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(List.of(product, number));
        when(compiler.compile(eq(product), eq("relation"), isNull())).thenReturn(new net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition("productId", "product_id", net.ximatai.muyun.spring.dynamic.metadata.FieldType.STRING, "商品"));
        when(compiler.compile(eq(number), eq("relation"), isNull())).thenReturn(new net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition("documentNo", "document_no", net.ximatai.muyun.spring.dynamic.metadata.FieldType.STRING, "单号"));
        when(references.findForRelation("product", "relation")).thenReturn(new net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfig());
        var service = new CodeConfigurationService(catalog, modules, relations, schemas, fields, bindings, compiler, references, mock(net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService.class));
        var target = service.targets("sample.order").getFirst();
        assertThat(target.fields()).extracting(CodeConfigurationService.Field::fieldName).containsExactly("documentNo");
        assertThat(target.sources()).extracting(CodeConfigurationService.Source::fieldName).contains("productId");
    }

    @Test void dynamicDraftsCompileWithoutInstallingAFieldAndUseTheirProposedTrigger() {
        doReturn(List.of(new CodeConfigurationService.Target("api.invoice", "invoice", "单据", "DYNAMIC", "relation",
                List.of(new CodeConfigurationService.Field("documentNo", "编码", 32, "field", null, null, null, null)), List.of(), "单据", null)))
                .when(configuration).targets("api.invoice");
        var segment = segment(CodeSegmentType.CONTEXT_VAR, "externalYear"); segment.setLength(8); segment.setTruncatePolicy(CodeTruncatePolicy.RIGHT);
        var rule = rule(segment); rule.setEnabled(false); rule.setFieldTrigger(CodeTrigger.EXPLICIT);
        assertThatCode(() -> configuration.validateRule(rule, rule.getSegments())).doesNotThrowAnyException();
        configuration.prepareActivation(rule);
        verify(configuration, never()).bind(any());
        assertThat(rule.getModuleMetadataFieldId()).isNull();
        assertThat(rule.getFieldTrigger()).isEqualTo(CodeTrigger.EXPLICIT);
    }

    @Test void firstActivationRequiresTheFieldConfigurationPermissionBeforeAnyBindingWrite() {
        var permission = mock(net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService.class);
        var scoped = spy(new CodeConfigurationService(null, null, null, null, null, null, null,
                mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService.class), permission));
        doReturn(new CodeConfigurationService.Target("api.invoice", "invoice", "单据", "DYNAMIC", "relation",
                List.of(new CodeConfigurationService.Field("documentNo", "编码", 32, "field", null, null, null, null)), List.of(), "单据", null))
                .when(scoped).requireTarget("api.invoice", "invoice");
        doThrow(new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("denied")).when(permission).requireAuthorized(any());
        var rule = rule(segment(CodeSegmentType.CONSTANT, null)); rule.setEnabled(true);
        assertThatThrownBy(() -> scoped.prepareActivation(rule)).hasMessageContaining("denied");
        verify(scoped, never()).bind(any());
    }

    @Test void staticBindingsCannotBeMutatedThroughGovernance() {
        target(CodeFieldRole.NORMAL, CodeTrigger.SAVE);
        assertThatThrownBy(() -> configuration.bind(new CodeConfigurationService.BindingCommand("api.invoice", "invoice", "documentNo", CodeFieldRole.PRIMARY, CodeTrigger.SAVE, null)))
                .hasMessageContaining("declared by the Java model");
    }
}
