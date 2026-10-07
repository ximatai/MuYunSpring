package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldBehaviorSupportTest {
    @Test
    void defaultsMustFitTheNormalizedPhysicalShape() {
        FieldDefinition text = FieldDefinition.string("code", "Code").length(2)
                .writeRules(new FieldWriteRules(false, false, TextNormalization.TRIM));
        FieldBehaviorSupport.validateBehavior(text.defaultValue(" AB "));
        FieldBehaviorSupport.validateBehavior(text.defaultValue("😀😀"));
        assertThatThrownBy(() -> FieldBehaviorSupport.validateBehavior(text.defaultValue("ABC")))
                .hasMessageContaining("field length");
        FieldDefinition amount = FieldDefinition.decimal("amount", "Amount").precision(4, 2);
        for (String value : List.of("99.99", "1.2300", "0", "-99.99")) {
            FieldBehaviorSupport.validateBehavior(amount.defaultValue(value));
        }
        assertThatThrownBy(() -> FieldBehaviorSupport.validateBehavior(amount.defaultValue("100")))
                .hasMessageContaining("field precision");
        assertThatThrownBy(() -> FieldBehaviorSupport.validateBehavior(amount.defaultValue("1e3")))
                .hasMessageContaining("field precision");
        assertThatThrownBy(() -> FieldBehaviorSupport.validateBehavior(amount.defaultValue("1e2147483647")))
                .hasMessageContaining("field precision");
        assertThatThrownBy(() -> FieldBehaviorSupport.validateBehavior(amount.defaultValue("0.001")))
                .hasMessageContaining("field scale");
        FieldBehaviorSupport.validateBehavior(FieldDefinition.decimal("ratio", "Ratio").precision(2, 2).defaultValue("0"));
    }

    @Test
    void jsonDefaultsAreTypedValuesAndMustBeOneCompleteJsonValue() {
        assertThat(FieldBehaviorSupport.parseDefaultValue(FieldType.JSON, "[\"NEW\",\"DONE\"]"))
                .isEqualTo(List.of("NEW", "DONE"));
        assertThat(FieldBehaviorSupport.parseDefaultValue(FieldType.STRING, "NEW")).isEqualTo("NEW");
        for (String invalid : List.of("[NEW]", "[] []", "")) {
            assertThatThrownBy(() -> FieldBehaviorSupport.parseDefaultValue(FieldType.JSON, invalid))
                    .hasMessageContaining("invalid JSON value");
        }
    }

    @Test
    void jsonSetDefaultsMustRespectTheirCollectionShapeAndDeclaredNullability() {
        FieldDefinition tags = FieldDefinition.of("tags", FieldType.JSON, "Tags").jsonSet();
        FieldBehaviorSupport.validateBehavior(tags.defaultValue("[\"NEW\"]"));
        for (String value : List.of("{}", "[1]", "\"NEW\"")) {
            assertThatThrownBy(() -> FieldBehaviorSupport.validateBehavior(tags.defaultValue(value)))
                    .hasMessageContaining("requires a string collection");
        }
        assertThatThrownBy(() -> FieldBehaviorSupport.validateBehavior(tags.required().defaultValue("null")))
                .hasMessageContaining("must not be null");
    }
}
