package net.ximatai.muyun.spring.platform.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageCompositionDraftCompilerTest {
    private static final String KEY = "a".repeat(32);
    private static final String TREE = """
            {"nodes":[{"slot":"form","fields":[{"field":"field%s"}]}]}
            """.formatted(KEY);

    @Test
    void shouldCreateOnlySupportedNullableFieldsWithStableNames() {
        Map.of("text", "string", "textarea", "text", "number", "decimal", "date", "date", "switch", "boolean")
                .forEach((component, spec) -> {
                    var field = PageCompositionDraftCompiler.fieldDrafts(TREE,
                            List.of(new PageCompositionSaveCommand.NewField(KEY, " 名称 ", component))).getFirst().field();
                    assertThat(field.getFieldName()).isEqualTo("field" + KEY);
                    assertThat(field.getColumnName()).isEqualTo(field.getFieldName());
                    assertThat(field.getTitle()).isEqualTo("名称");
                    assertThat(field.getFieldSpecAlias()).isEqualTo(spec);
                });
    }

    @Test
    void shouldCarryRequiredRuleIntoMetadataAndDefaultToOptional() {
        var required = PageCompositionDraftCompiler.fieldDrafts(TREE,
                List.of(new PageCompositionSaveCommand.NewField(KEY, "名称", "text", true))).getFirst().field();
        assertThat(required.getRequired()).isTrue();
        var optional = PageCompositionDraftCompiler.fieldDrafts(TREE,
                List.of(new PageCompositionSaveCommand.NewField(KEY, "名称", "text", null))).getFirst().field();
        assertThat(optional.getRequired()).isFalse();
    }

    @Test
    void shouldKeepFieldsUsedOnlyByNavigationOrQueriesWithoutCrossingChildScope() {
        var input = new PageCompositionSaveCommand.NewField(KEY, "名称", "text");
        for (String tree : List.of(
                "{\"quickSearchFields\":[\"field%s\"],\"nodes\":[]}",
                "{\"nodes\":[{\"slot\":\"explorer\",\"titleField\":\"field%s\"}]}",
                "{\"nodes\":[{\"slot\":\"explorer\",\"secondaryField\":\"field%s\"}]}",
                "{\"querySummaries\":[{\"fieldName\":\"field%s\"}],\"nodes\":[]}")) {
            assertThat(PageCompositionDraftCompiler.fieldDrafts(tree.formatted(KEY), List.of(input))).hasSize(1);
        }
        assertThatThrownBy(() -> PageCompositionDraftCompiler.fieldDrafts(
                "{\"nodes\":[{\"slot\":\"form\",\"relations\":[{\"fields\":[\"field%s\"]}]}]}".formatted(KEY), List.of(input)))
                .hasMessageContaining("未放入页面结构");
    }

    @Test
    void shouldRejectDuplicateUnsupportedOrUnplacedComponents() {
        var input = new PageCompositionSaveCommand.NewField(KEY, "名称", "text");
        assertThatThrownBy(() -> PageCompositionDraftCompiler.fieldDrafts(TREE, List.of(input, input)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageCompositionDraftCompiler.fieldDrafts(TREE,
                List.of(new PageCompositionSaveCommand.NewField(KEY, "名称", "reference"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageCompositionDraftCompiler.fieldDrafts("{\"nodes\":[]}", List.of(input)))
                .hasMessageContaining("未放入页面结构");
    }
}
