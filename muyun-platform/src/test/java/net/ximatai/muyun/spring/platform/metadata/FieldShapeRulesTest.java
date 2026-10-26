package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.DeclarationValidationException;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldShapeRulesTest {
    @Test
    void everyShapeFailureSurvivesTheStructuredPreviewBoundary() {
        var loader = getClass().getClassLoader();
        var resources = TextResources.load(loader, LanguageCatalog.load(loader, "zh-CN"));
        List<Runnable> invalid = List.of(
                () -> FieldShapeRules.validate(FieldType.STRING, 0, null, null, "amount {context}"),
                () -> FieldShapeRules.validate(FieldType.DECIMAL, 4, null, null, "amount {context}"),
                () -> FieldShapeRules.validate(FieldType.DECIMAL, null, 0, null, "amount {context}"),
                () -> FieldShapeRules.validate(FieldType.DECIMAL, null, null, -1, "amount {context}"),
                () -> FieldShapeRules.validate(FieldType.STRING, null, 4, null, "amount {context}"),
                () -> FieldShapeRules.validate(FieldType.DECIMAL, null, null, 1, "amount {context}"),
                () -> FieldShapeRules.validate(FieldType.DECIMAL, null, 2, 3, "amount {context}"));
        for (Runnable check : invalid) {
            var failure = assertThrows(DeclarationValidationException.class, check::run);
            var issue = MetadataChangeSetValidationIssue.failure("INVALID_FIELD_DRAFT", "amount", failure);
            assertThat(issue.code()).isEqualTo("INVALID_FIELD_DRAFT");
            assertThat(issue.subject()).isEqualTo("amount");
            assertThat(issue.severity()).isEqualTo(MetadataChangeSetValidationIssue.Severity.ERROR);
            assertThat(issue.message()).isEqualTo(failure.getMessage());
            for (String language : List.of("en", "zh-CN")) {
                var translated = resources.resolve(issue.textSource(), issue.messageArgs(), language);
                assertThat(translated.missing()).isFalse();
                assertThat(translated.fallback()).isFalse();
                assertThat(translated.text()).contains("amount {context}");
            }
        }
        FieldShapeRules.validate(FieldType.DECIMAL, null, 4, 2, "amount");
        FieldShapeRules.validate(FieldType.STRING, 32, null, null, "title");
    }
}
