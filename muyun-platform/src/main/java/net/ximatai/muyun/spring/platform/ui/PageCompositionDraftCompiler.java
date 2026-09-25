package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldChangeSetDraft;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts temporary page components into metadata drafts for preview and save. */
public final class PageCompositionDraftCompiler {
    private PageCompositionDraftCompiler() {}
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public record ComponentDefinition(String component, String title, String fieldSpecAlias) {}
    private static final List<ComponentDefinition> COMPONENTS = List.of(
            new ComponentDefinition("text", "单行文本", "string"),
            new ComponentDefinition("textarea", "多行文本", "text"),
            new ComponentDefinition("number", "数字", "decimal"),
            new ComponentDefinition("date", "日期", "date"),
            new ComponentDefinition("switch", "开关", "boolean"));
    private static final Map<String, String> SPECS = COMPONENTS.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(ComponentDefinition::component, ComponentDefinition::fieldSpecAlias));

    public static List<ComponentDefinition> components() { return COMPONENTS; }

    public static List<MetadataFieldChangeSetDraft> fieldDrafts(String uiTreeJson, List<PageCompositionSaveCommand.NewField> inputs) {
        if (inputs == null || inputs.size() > 100) throw new IllegalArgumentException("新增组件数量无效");
        Set<String> placements = placedFields(uiTreeJson);
        Set<String> keys = new HashSet<>();
        List<MetadataFieldChangeSetDraft> drafts = new ArrayList<>();
        for (var input : inputs) {
            if (input == null || input.key() == null || !input.key().matches("[a-f0-9]{32}")
                    || !keys.add(input.key()) || input.title() == null || input.title().isBlank()
                    || input.title().trim().length() > 128 || (input.component() == null || !SPECS.containsKey(input.component()))) {
                throw new IllegalArgumentException("新增组件的名称或类型无效");
            }
            String name = "field" + input.key();
            if (!placements.contains(name)) throw new IllegalArgumentException("新增组件未放入页面结构");
            MetadataField field = new MetadataField();
            field.setFieldName(name);
            field.setColumnName(name);
            field.setTitle(input.title().trim());
            field.setFieldSpecAlias(SPECS.get(input.component()));
            field.setRequired(Boolean.TRUE.equals(input.required()));
            field.setEnabled(true);
            drafts.add(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null, field));
        }
        return List.copyOf(drafts);
    }

    public static String childAlias(String key) {
        if (key == null || !key.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("明细表标识无效");
        return "detail_" + key;
    }

    /** Validates every temporary child against its own direct page placement. */
    public static Map<String, List<MetadataFieldChangeSetDraft>> childDrafts(String json,
            List<PageCompositionSaveCommand.NewChild> children) {
        if (children == null || children.size() > 20) throw new IllegalArgumentException("新增明细表数量无效");
        Map<String, List<MetadataFieldChangeSetDraft>> result = new java.util.LinkedHashMap<>();
        try {
            JsonNode tree = OBJECT_MAPPER.readTree(json);
            for (var child : children) {
                String alias = childAlias(child.key());
                if (result.containsKey(alias) || child.title() == null || child.title().isBlank()
                        || child.title().trim().length() > 128 || child.fields() == null)
                    throw new IllegalArgumentException("明细表名称或字段无效");
                JsonNode placement = null;
                for (JsonNode node : tree.path("nodes")) {
                    if (!"form".equals(node.path("slot").asText())) continue;
                    for (JsonNode relation : node.path("relations"))
                        if (alias.equals(relation.path("relation").asText())) placement = relation;
                }
                if (placement == null) throw new IllegalArgumentException("新增明细表未放入页面结构");
                var form = OBJECT_MAPPER.createObjectNode().put("slot", "form");
                form.set("fields", placement.path("fields"));
                var input = OBJECT_MAPPER.createObjectNode();
                input.putArray("nodes").add(form);
                result.put(alias, fieldDrafts(input.toString(), child.fields()));
            }
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("页面结构无效", e);
        }
    }

    private static Set<String> placedFields(String json) {
        try {
            Set<String> fields = new HashSet<>();
            JsonNode tree = OBJECT_MAPPER.readTree(json);
            collect(tree.path("quickSearchFields"), fields);
            for (JsonNode summary : tree.path("querySummaries")) {
                fields.add(summary.path("fieldName").asText());
                fields.add(summary.path("groupByField").asText());
            }
            for (JsonNode node : tree.path("nodes")) {
                if ("explorer".equals(node.path("slot").asText())) {
                    fields.add(node.path("titleField").asText());
                    fields.add(node.path("secondaryField").asText());
                }
                if (!Set.of("list", "form", "detail").contains(node.path("slot").asText())) continue;
                collect(node.path("fields"), fields);
                for (JsonNode group : node.path("groups")) collect(group.path("fields"), fields);
            }
            return fields;
        } catch (Exception failure) {
            throw new IllegalArgumentException("页面结构无效", failure);
        }
    }

    private static void collect(JsonNode entries, Set<String> fields) {
        for (JsonNode entry : entries) fields.add(entry.isTextual() ? entry.asText() : entry.path("field").asText());
    }
}
