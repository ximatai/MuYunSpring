package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageCompositionFieldNamingTest {
    @Test
    void shouldAvoidExistingNamesPhysicalColumnsReservedNamesAndBatchDuplicates() {
        var inputs = inputs("yuSuan", "yuSuan", "id", "tenantId");
        var existing = new MetadataField();
        existing.setFieldName("budget");
        existing.setColumnName("yu_suan");
        var drafts = PageCompositionDraftCompiler.fieldDrafts(tree(inputs), inputs);
        var names = PageCompositionFieldNaming.assign(inputs, drafts, List.of(existing));
        assertThat(names.values()).containsExactly("yuSuan2", "yuSuan3", "id2", "tenantId2");
        assertThat(drafts.getFirst().field().getColumnName()).isEqualTo("yu_suan2");
    }

    @Test
    void shouldBoundBothIdentifiersAndStillDeduplicateLongNames() {
        String longName = "aB".repeat(31) + "a";
        var inputs = inputs(longName, longName);
        var drafts = PageCompositionDraftCompiler.fieldDrafts(tree(inputs), inputs);
        PageCompositionFieldNaming.assign(inputs, drafts, List.of());
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.field().getFieldName()).hasSizeLessThanOrEqualTo(63);
            assertThat(draft.field().getColumnName()).hasSizeLessThanOrEqualTo(63);
        });
        assertThat(drafts.get(1).field().getFieldName()).isNotEqualTo(drafts.getFirst().field().getFieldName());
        var invalid = inputs("bad_name");
        assertThatThrownBy(() -> PageCompositionFieldNaming.assign(invalid,
                PageCompositionDraftCompiler.fieldDrafts(tree(invalid), invalid), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRewriteDeclaredReferencesInTheirOwnScopeWithoutChangingLabels() throws Exception {
        String tree = """
                {"quickSearchFields":["temp"],"querySummaries":[{"fieldName":"temp","groupByField":"temp"}],
                 "nodes":[{"slot":"list","fields":["temp"]},
                  {"slot":"explorer","titleField":"temp","secondaryField":"temp"},
                  {"slot":"detail","fields":["temp"]},
                  {"slot":"form","fields":[{"field":"temp","props":{"label":"temp"}}],
                   "order":[{"field":"temp"}],"groups":[{"fields":["temp"]}],
                   "relations":[{"relation":"child","fields":["temp"]},
                                {"relation":"existing","fields":["temp"]}]}]}
                """;
        var saved = new ObjectMapper().readTree(PageCompositionFieldNaming.rewrite(tree,
                Map.of("temp", "mainName"), Map.of("child", Map.of("temp", "childName"))));
        for (String pointer : List.of("/quickSearchFields/0", "/querySummaries/0/fieldName",
                "/querySummaries/0/groupByField", "/nodes/0/fields/0", "/nodes/1/titleField",
                "/nodes/1/secondaryField", "/nodes/2/fields/0", "/nodes/3/fields/0/field",
                "/nodes/3/order/0/field", "/nodes/3/groups/0/fields/0")) {
            assertThat(saved.at(pointer).asText()).as(pointer).isEqualTo("mainName");
        }
        assertThat(saved.at("/nodes/3/fields/0/props/label").asText()).isEqualTo("temp");
        assertThat(saved.at("/nodes/3/relations/0/fields/0").asText()).isEqualTo("childName");
        assertThat(saved.at("/nodes/3/relations/1/fields/0").asText()).isEqualTo("temp");
    }

    private static List<PageCompositionSaveCommand.NewField> inputs(String... names) {
        return IntStream.range(0, names.length).mapToObj(index ->
                new PageCompositionSaveCommand.NewField(String.format("%032x", index), "标题", "text", false, names[index])).toList();
    }

    private static String tree(List<PageCompositionSaveCommand.NewField> inputs) {
        return "{\"nodes\":[{\"slot\":\"form\",\"fields\":[" + inputs.stream()
                .map(input -> "\"field" + input.key() + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}]}";
    }
}
