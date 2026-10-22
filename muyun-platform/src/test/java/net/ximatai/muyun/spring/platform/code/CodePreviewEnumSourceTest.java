package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static net.ximatai.muyun.spring.platform.code.fixture.CodeRuleFixtures.deviceTypeMapping;

class CodePreviewEnumSourceTest {
    private final CodePreviewService preview = new CodePreviewService();

    enum DeviceType implements CodeTitleEnum {
        MR_GLASSES("mr_glasses"), SAFETY_HELMET("safety_helmet");
        private final String code;
        DeviceType(String code) { this.code = code; }
        public String getCode() { return code; }
        public String getTitle() { return "display title"; }
        @Override public String toString() { return "presentation must not become a code"; }
    }
    enum OrdinaryType {
        STANDARD;
        @Override public String toString() { return "display-only"; }
    }

    @Test void enumObjectAndWireCodeProduceIdenticalMappingsAndBasisSegmentsWithoutMutatingInput() {
        var rule = deviceTypeMapping();
        for (var type : DeviceType.values()) {
            Map<String, Object> model = Map.of("deviceType", type);
            var typed = render(rule, model);
            var wire = render(rule, Map.of("deviceType", type.getCode()));
            assertThat(typed).isEqualTo(wire);
            assertThat(typed.value()).isEqualTo(type == DeviceType.MR_GLASSES ? "GL-000001" : "HM-000001");
            assertThat(typed.segments().getFirst().sequenceBasis()).isTrue();
            assertThat(model.get("deviceType")).isSameAs(type);
        }
    }

    @Test void fieldAndFormulaSourcesUsePublicOptionValuesIncludingQualifiedParentSources() {
        var field = source(CodeSegmentType.FIELD_VALUE, "parent.deviceType");
        var formula = source(CodeSegmentType.FORMULA, null); formula.setFormulaExpr("{deviceType}");
        var plain = source(CodeSegmentType.FIELD_VALUE, "ordinaryType");
        var rule = new CodeRule(); rule.setSegments(List.of(field, formula, plain));
        assertThat(render(rule, Map.of(
                "parent.deviceType", DeviceType.SAFETY_HELMET, "deviceType", DeviceType.MR_GLASSES,
                "ordinaryType", OrdinaryType.STANDARD)).value())
                .isEqualTo("safety_helmetmr_glassesSTANDARD");
    }

    @Test void unknownAndMissingSourcesKeepTheirConfiguredPolicies() {
        var rule = deviceTypeMapping();
        assertThatThrownBy(() -> render(rule, Map.of("deviceType", "unknown")))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.code()).isEqualTo(CodePreviewService.VALUE_MAPPING_MISSING));
        assertThatThrownBy(() -> render(rule, Map.of()))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.code()).isEqualTo(CodePreviewService.SOURCE_VALUE_MISSING));
        var mapping = rule.getSegments().getFirst();
        var fallback = new CodeValueMapping(); fallback.setDefaultMapping(true); fallback.setTargetValue("OTHER-");
        mapping.setMappings(List.of(fallback));
        assertThat(render(rule, Map.of("deviceType", "unknown")).value()).isEqualTo("OTHER-000001");
        mapping.setNullPolicy(CodeNullPolicy.SKIP_SEGMENT);
        assertThat(render(rule, Map.of()).value()).isEqualTo("000001");
        mapping.setNullPolicy(CodeNullPolicy.USE_DEFAULT); mapping.setFixedValue("fallback-source");
        assertThat(render(rule, Map.of()).value()).isEqualTo("OTHER-000001");
    }

    @Test void nonEnumDatesNumbersAndReferenceIdsKeepTheirExistingRendering() {
        var date = source(CodeSegmentType.FIELD_VALUE, "at"); date.setDateFormat(CodeDateFormat.YYYYMMDD);
        var rule = new CodeRule(); rule.setSegments(List.of(date, source(CodeSegmentType.FIELD_VALUE, "amount"), source(CodeSegmentType.FIELD_VALUE, "referenceId")));
        assertThat(render(rule, Map.of("at", LocalDateTime.of(2026, 10, 22, 12, 0),
                "amount", new BigDecimal("12.30"), "referenceId", " ID-001 ")).value()).isEqualTo("2026102212.30 ID-001 ");
    }

    private CodePreviewResult render(CodeRule rule, Map<String, Object> context) {
        return preview.previewDraft(new PreviewCodeRuleCommand(rule, context, null, null, 1L));
    }

    private static CodeRuleSegment source(CodeSegmentType type, String ref) {
        var segment = new CodeRuleSegment(); segment.setSegmentType(type); segment.setSourceRef(ref); return segment;
    }
}
