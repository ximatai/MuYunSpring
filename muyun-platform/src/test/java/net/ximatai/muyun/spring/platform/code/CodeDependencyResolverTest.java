package net.ximatai.muyun.spring.platform.code;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class CodeDependencyResolverTest {
    private final CodeDependencyResolver dependencies = new CodeDependencyResolver();

    @Test void formulaReferencesUseTheSameNormalizedNamesAsEvaluationForOrderingAndLinkedUpdates() {
        var consumer = formula("codeA", "{ codeB }");
        var source = formula("codeB", "'B'");
        assertThat(dependencies.orderedRules(List.of(consumer, source), "main"))
                .extracting(item -> item.rule().getFieldName()).containsExactly("codeB", "codeA");
        assertThat(dependencies.dependsOnChangedField(consumer.rule(), Map.of("codeB", "old"),
                Map.of("codeB", "new"), Set.of("codeB"))).isTrue();
        assertThat(dependencies.dependsOnChangedField(consumer.rule(), Map.of("codeB", "same"),
                Map.of("codeB", "same"), Set.of("codeB"))).isFalse();
    }
    @Test void normalizedReferencesCannotHideCyclesIncludingEntityQualifiedReferences() {
        var first = formula("codeA", "{ main.codeB }");
        var second = formula("codeB", "{ codeA }");
        assertThatThrownBy(() -> dependencies.orderedRules(List.of(first, second), "main"))
                .hasMessageContaining("Cyclic business code dependency");
    }
    @Test void quotedBracesAreLiteralTextAndDoNotCreateDependenciesOrLinkedChanges() {
        var literal = formula("codeA", "'{codeB}'");
        var consumer = formula("codeB", "{ codeA }");
        assertThat(dependencies.dependencyKeys(literal.rule())).isEmpty();
        assertThat(dependencies.orderedRules(List.of(literal, consumer), "main"))
                .extracting(item -> item.rule().getFieldName()).containsExactly("codeA", "codeB");
        assertThat(dependencies.dependsOnChangedField(literal.rule(), Map.of("codeB", "old"),
                Map.of("codeB", "new"), Set.of("codeB"))).isFalse();
    }
    private ResolvedCodeRule formula(String field, String expression) {
        var rule = new CodeRule(); rule.setFieldName(field);
        var segment = new CodeRuleSegment(); segment.setSegmentType(CodeSegmentType.FORMULA); segment.setFormulaExpr(expression);
        rule.setSegments(List.of(segment)); return new ResolvedCodeRule(rule, null);
    }
}
