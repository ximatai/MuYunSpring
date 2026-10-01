package net.ximatai.muyun.spring.common.model.constraint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class StaticFieldWriteRulesTest {
    static class PatternRecord {
        @FieldPattern("[a-z][a-z0-9_]{0,62}") String alias;
    }
    static class BlankPatternRecord {
        @FieldPattern("") String alias;
    }
    static class InvalidPatternRecord {
        @FieldPattern("[") String alias;
    }
    @Test
    void patternUsesFullStringSemanticsOnBothWriteOperationsAndAllowsNull() {
        var record = new PatternRecord();
        StaticFieldWriteRules.validate(PatternRecord.class, record, WriteOperation.INSERT);
        record.alias = "sales_order";
        StaticFieldWriteRules.validate(PatternRecord.class, record, WriteOperation.INSERT);
        for (String invalid : java.util.List.of("中文", "Sales", "sales\n", "")) {
            record.alias = invalid;
            for (var operation : WriteOperation.values()) {
                assertThatThrownBy(() -> StaticFieldWriteRules.validate(PatternRecord.class, record, operation))
                        .hasMessageContaining("alias");
            }
        }
        assertThatThrownBy(() -> StaticFieldWriteRules.resolve(BlankPatternRecord.class))
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> StaticFieldWriteRules.resolve(InvalidPatternRecord.class))
                .isInstanceOf(java.util.regex.PatternSyntaxException.class);
    }

    @Test
    void namedInheritedRulesAreModelLocalAndEachFacetCanBeSpecializedIndependently() {
        Child value = new Child();
        value.title = "  Kept  ";
        StaticFieldWriteRules.normalize(Child.class, value);
        assertThat(value.title).isEqualTo("Kept");
        assertThat(StaticFieldWriteRules.resolve(Child.class).get("title").rules())
                .isEqualTo(new FieldWriteRules(false, true, TextNormalization.TRIM));
        assertThat(StaticFieldWriteRules.resolve(Sibling.class)).isEmpty();
        value.title = null;
        StaticFieldWriteRules.validate(Child.class, value, WriteOperation.INSERT);
        assertThatThrownBy(() -> StaticFieldWriteRules.validate(Child.class, value, WriteOperation.UPDATE))
                .hasMessageContaining("title");
    }

    @Test
    void duplicateUnknownAndShadowedNamesFailAtCompilationInsteadOfSilentlyIgnoringRules() {
        assertThatThrownBy(() -> StaticFieldWriteRules.resolve(Unknown.class)).hasMessageContaining("unknown");
        assertThatThrownBy(() -> StaticFieldWriteRules.resolve(Duplicate.class)).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> StaticFieldWriteRules.resolve(Shadow.class)).hasMessageContaining("shadowed");
        assertThatThrownBy(() -> StaticFieldWriteRules.resolve(MissingNames.class)).hasMessageContaining("field names");
    }

    @Test
    void repeatableDeclarationsAllowDifferentRequirementsWithoutReplacingOtherFields() {
        var rules = StaticFieldWriteRules.resolve(Stages.class);
        assertThat(rules.get("title").rules().requiredOnInsert()).isTrue();
        assertThat(rules.get("title").rules().requiredOnUpdate()).isFalse();
        assertThat(rules.get("reason").rules().requiredOnInsert()).isFalse();
        assertThat(rules.get("reason").rules().requiredOnUpdate()).isTrue();
    }

    static class Base { String title; String reason; }
    @Required(fields = "title") @NormalizeText(fields = "title")
    static class Parent extends Base {}
    @Required(fields = "title", on = WriteOperation.UPDATE)
    static class Child extends Parent {}
    static class Sibling extends Base {}
    @Required(fields = "unknown") static class Unknown extends Base {}
    @Required(fields = "title") @Required(fields = "title", on = WriteOperation.UPDATE)
    static class Duplicate extends Base {}
    static class AnnotatedBase { @Required String title; }
    static class Shadow extends AnnotatedBase { String title; }
    @Required static class MissingNames extends Base {}
    @Required(fields = "title", on = WriteOperation.INSERT)
    @Required(fields = "reason", on = WriteOperation.UPDATE)
    static class Stages extends Base {}
}
