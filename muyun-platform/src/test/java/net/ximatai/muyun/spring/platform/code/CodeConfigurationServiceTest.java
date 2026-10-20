package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.code.CodeTrigger;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeConfigurationServiceTest {
    @Test void staticCatalogUsesTheBusinessModuleTitleWithoutRequiringDynamicMetadataOrAPage() {
        var catalog = mock(CodeTargetCatalog.class);
        var modules = mock(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class);
        var service = mock(net.ximatai.muyun.spring.ability.code.CodeAbility.class);
        when(service.getModuleAlias()).thenReturn("api.invoice"); when(service.codeEntityAlias()).thenReturn("invoice");
        doReturn(net.ximatai.muyun.spring.platform.code.fixture.StaticCodeRecord.class).when(service).modelClass();
        when(catalog.staticTargets()).thenReturn(List.of(service));
        var module = new net.ximatai.muyun.spring.platform.module.PlatformModule(); module.setAlias("api.invoice"); module.setTitle("业务单据");
        when(modules.resolveVisibleModule("api.invoice")).thenReturn(module);
        var configuration = new CodeConfigurationService(catalog, modules, null, null, null, null, null);
        assertThat(configuration.targets("api.invoice")).singleElement().satisfies(target -> {
            assertThat(target.title()).isEqualTo("业务单据"); assertThat(target.entityAlias()).isEqualTo("invoice");
            assertThat(target.relationId()).isNull(); assertThat(target.moduleTitle()).isEqualTo("业务单据");
        });
        when(modules.resolveVisibleModule("api.invoice")).thenReturn(null);
        assertThat(configuration.targets("api.invoice")).singleElement()
                .extracting(CodeConfigurationService.Target::title).isEqualTo("Static business coding contract");
    }
    private final CodeConfigurationService configuration = spy(new CodeConfigurationService(null, null, null, null, null, null, null));
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
    @Test void staticBindingsCannotBeMutatedThroughGovernance() {
        target(CodeFieldRole.NORMAL, CodeTrigger.SAVE);
        assertThatThrownBy(() -> configuration.bind(new CodeConfigurationService.BindingCommand("api.invoice", "invoice", "documentNo", CodeFieldRole.PRIMARY, CodeTrigger.SAVE, null)))
                .hasMessageContaining("declared by the Java model");
    }
}
