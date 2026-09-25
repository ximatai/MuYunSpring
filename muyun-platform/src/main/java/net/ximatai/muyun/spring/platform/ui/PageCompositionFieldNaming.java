package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordProtocolFields;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldChangeSetDraft;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Assigns names within one metadata entity and rewrites only declared page field references. */
final class PageCompositionFieldNaming {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PageCompositionFieldNaming() {}

    static Map<String, String> assign(List<PageCompositionSaveCommand.NewField> inputs,
                                    List<MetadataFieldChangeSetDraft> drafts, List<MetadataField> existing) {
        Set<String> names = new HashSet<>(DynamicRecordProtocolFields.reservedBusinessFieldNames());
        Set<String> columns = new HashSet<>();
        names.forEach(name -> columns.add(columnName(name)));
        for (MetadataField field : existing) {
            names.add(field.getFieldName());
            if (field.getColumnName() != null) columns.add(field.getColumnName().toLowerCase(Locale.ROOT));
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int index = 0; index < inputs.size(); index++) {
            var input = inputs.get(index);
            var field = drafts.get(index).field();
            String temporary = field.getFieldName();
            String base = input.suggestedName() == null ? temporary
                    : PlatformNameRules.requireFieldName(input.suggestedName(), "suggestedName");
            String candidate;
            int number = 1;
            do {
                String suffix = number == 1 ? "" : Integer.toString(number);
                int length = Math.min(base.length(), PlatformNameRules.IDENTIFIER_MAX_LENGTH - suffix.length());
                while (columnName(base.substring(0, length) + suffix).length() > PlatformNameRules.IDENTIFIER_MAX_LENGTH)
                    length--;
                candidate = base.substring(0, length) + suffix;
                number++;
            } while (names.contains(candidate) || columns.contains(columnName(candidate)));
            names.add(candidate);
            columns.add(columnName(candidate));
            field.setFieldName(candidate);
            field.setColumnName(columnName(candidate));
            mapping.put(temporary, candidate);
        }
        return mapping;
    }

    private static String columnName(String fieldName) {
        return fieldName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    static String rewrite(String json, Map<String, String> main, Map<String, Map<String, String>> children) {
        try {
            JsonNode tree = JSON.readTree(json);
            rewriteArray(tree.path("quickSearchFields"), main);
            for (JsonNode summary : tree.path("querySummaries")) {
                rewriteProperty(summary, "fieldName", main);
                rewriteProperty(summary, "groupByField", main);
            }
            for (JsonNode node : tree.path("nodes")) {
                rewriteProperty(node, "titleField", main);
                rewriteProperty(node, "secondaryField", main);
                rewriteLayout(node, main);
                for (JsonNode relation : node.path("relations")) {
                    var mapping = children.get(relation.path("relation").asText());
                    if (mapping != null) rewriteLayout(relation, mapping);
                }
            }
            return JSON.writeValueAsString(tree);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("页面结构无效", failure);
        }
    }

    private static void rewriteLayout(JsonNode layout, Map<String, String> mapping) {
        rewriteArray(layout.path("fields"), mapping);
        for (JsonNode item : layout.path("order")) rewriteProperty(item, "field", mapping);
        for (JsonNode group : layout.path("groups")) rewriteLayout(group, mapping);
    }

    private static void rewriteArray(JsonNode fields, Map<String, String> mapping) {
        if (!(fields instanceof ArrayNode array)) return;
        for (int index = 0; index < array.size(); index++) {
            JsonNode field = array.get(index);
            if (field.isTextual()) array.set(index, JSON.getNodeFactory().textNode(mapping.getOrDefault(field.asText(), field.asText())));
            else rewriteProperty(field, "field", mapping);
        }
    }

    private static void rewriteProperty(JsonNode node, String key, Map<String, String> mapping) {
        if (!(node instanceof ObjectNode object) || !node.path(key).isTextual()) return;
        String replacement = mapping.get(node.path(key).asText());
        if (replacement != null) object.put(key, replacement);
    }
}
