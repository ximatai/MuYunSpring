package net.ximatai.muyun.spring.common.model.constraint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class StaticFieldWriteRulesTest {
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
