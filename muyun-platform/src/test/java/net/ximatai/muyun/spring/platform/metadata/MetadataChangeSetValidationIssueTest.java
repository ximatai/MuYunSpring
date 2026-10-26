package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataChangeSetValidationIssueTest {
    @Test
    void preservesOriginalMachineFactsAndLiteralArgumentsAcrossLanguages() {
        var source = TextReference.code("platform.metadata.diagnostic.field-spec-change-with-data",
                "字段“{title}”已有 {count} 条业务数据；目标字段规格不在当前规格声明的数据安全转换范围内。");
        Map<String, Object> args = new HashMap<>(Map.of("title", "Cost {count}", "count", 7));
        var issue = MetadataChangeSetValidationIssue.declared(MetadataChangeSetValidationIssue.Severity.ERROR,
                "FIELD_SPEC_CHANGE_WITH_DATA", "amount", source, args);
        args.put("count", 99);
        var loader = getClass().getClassLoader();
        var resources = TextResources.load(loader, LanguageCatalog.load(loader, "zh-CN"));

        assertThat(issue.message()).contains("Cost {count}", "7 条");
        assertThat(resources.resolve(issue.textSource(), issue.messageArgs(), "en").text())
                .contains("Cost {count}", "7 business records").doesNotContain("99");
        assertThat(issue.code()).isEqualTo("FIELD_SPEC_CHANGE_WITH_DATA");
        assertThat(issue.subject()).isEqualTo("amount");
        assertThat(issue.severity()).isEqualTo(MetadataChangeSetValidationIssue.Severity.ERROR);
        assertThat(issue.messageArgs()).containsEntry("count", 7);
    }

    @Test
    void dynamicDeclarationSourcesSurviveBothMetadataAndBusinessRuleIssueBoundaries() {
        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException.class,
                () -> new net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator().validateField(
                        net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition.string("createdAt", "Created at")));
        var metadata = MetadataChangeSetValidationIssue.failure("INVALID_FIELD_DRAFT", "createdAt", failure);
        var rule = BusinessRuleIssue.failure("MODULE_DEFINITION_INVALID", "calculate", "createdAt", failure);
        assertThat(metadata.textSource()).isEqualTo(failure.textSource()).isEqualTo(rule.textSource());
        assertThat(metadata.messageArgs()).isEqualTo(failure.messageArgs()).isEqualTo(rule.messageArgs());
        assertThat(metadata.message()).isEqualTo(failure.getMessage()).isEqualTo(rule.message());
        assertThat(metadata.code()).isEqualTo("INVALID_FIELD_DRAFT");
        assertThat(metadata.subject()).isEqualTo("createdAt");
        assertThat(rule.code()).isEqualTo("MODULE_DEFINITION_INVALID");
        assertThat(rule.ruleCode()).isEqualTo("calculate");
        assertThat(rule.field()).isEqualTo("createdAt");
    }

    @Test
    void keepsDeclaredFailureSourceButDoesNotInventOneForTechnicalDiagnostics() {
        var source = TextReference.code("platform.metadata.test.referenced", "字段 {field} 被引用");
        var declared = PlatformErrors.badRequestText(source, Map.of("field", "amount"));
        var issue = MetadataChangeSetValidationIssue.failure("FIELD_CONFIGURATION_IN_USE", "amount", declared);
        assertThat(issue.textSource()).isEqualTo(source);
        assertThat(issue.messageArgs()).containsEntry("field", "amount");
        assertThat(issue.message()).isEqualTo(declared.getMessage());
        var technical = MetadataChangeSetValidationIssue.failure("INVALID_FIELD_DRAFT", "new",
                new IllegalArgumentException("third party {diagnostic}"));
        assertThat(technical.textSource()).isNull();
        assertThat(technical.messageArgs()).isEmpty();
        assertThat(technical.message()).isEqualTo("third party {diagnostic}");
    }
}
