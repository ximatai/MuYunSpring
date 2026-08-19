package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicFormulaFieldDefinitions;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DynamicFormulaDataSupport {
    private DynamicFormulaDataSupport() {
    }

    static List<FormulaFieldDefinition> fieldDefinitions(EntityDefinition entity, ModuleDefinition module) {
        List<FormulaFieldDefinition> definitions = new ArrayList<>(DynamicFormulaFieldDefinitions.mainFields(entity));
        if (module != null) {
            for (EntityRelationDefinition relation : module.relations()) {
                if (!entity.alias().equals(relation.parentEntityAlias())) {
                    continue;
                }
                module.entities().stream()
                        .filter(candidate -> relation.childEntityAlias().equals(candidate.alias()))
                        .findFirst()
                        .ifPresent(child -> definitions.addAll(
                                DynamicFormulaFieldDefinitions.childFields(relation.code(), child)
                        ));
            }
        }
        return List.copyOf(definitions);
    }

    static Map<String, Object> mainValues(DynamicRecord record, DynamicRecord existing) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (existing != null) {
            values.putAll(existing.getValues());
        }
        if (record != null) {
            values.putAll(record.getValues());
        }
        return values;
    }

    static Map<String, List<Map<String, Object>>> childValues(DynamicRecord record) {
        return childValues(record, Map.of());
    }

    static Map<String, List<Map<String, Object>>> childValues(DynamicRecord record,
                                                              Map<String, List<DynamicRecord>> existingChildren) {
        Map<String, List<Map<String, Object>>> values = new LinkedHashMap<>();
        Map<String, List<DynamicRecord>> existing = existingChildren == null ? Map.of() : existingChildren;
        for (Map.Entry<String, List<DynamicRecord>> entry : existing.entrySet()) {
            String relationCode = entry.getKey();
            List<DynamicRecord> submitted = record == null ? null : record.getChildren(relationCode);
            boolean hasSubmittedRelation = record != null && record.getChildren().containsKey(relationCode);
            if (!hasSubmittedRelation) {
                values.put(relationCode, childValueMaps(entry.getValue(), Map.of()));
            } else if (submitted != null) {
                values.put(relationCode, childValueMaps(submitted, existingChildrenById(entry.getValue())));
                if (record.isPartialChildren(relationCode)) {
                    appendUnsubmittedExistingRows(values.get(relationCode), entry.getValue(), submitted);
                }
            }
        }
        if (record == null) {
            return values;
        }
        record.getChildren().forEach((relationCode, children) -> {
            if (children == null || values.containsKey(relationCode)) {
                return;
            }
            values.put(relationCode, childValueMaps(children, Map.of()));
        });
        return values;
    }

    private static List<Map<String, Object>> childValueMaps(List<DynamicRecord> children,
                                                              Map<String, Map<String, Object>> existingById) {
        if (children == null || children.isEmpty()) {
            return new ArrayList<>();
        }
        return children.stream()
                .<Map<String, Object>>map(child -> mergedChildValues(child, existingById))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static void appendUnsubmittedExistingRows(List<Map<String, Object>> target,
                                                       List<DynamicRecord> existing,
                                                       List<DynamicRecord> submitted) {
        if (target == null || existing == null || existing.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> submittedById = existingChildrenById(submitted);
        for (DynamicRecord existingRow : existing) {
            String id = existingRow.getId();
            if (id != null && !id.isBlank() && submittedById.containsKey(id)) {
                continue;
            }
            target.add(new LinkedHashMap<>(existingRow.getValues()));
        }
    }

    private static Map<String, Map<String, Object>> existingChildrenById(List<DynamicRecord> children) {
        Map<String, Map<String, Object>> values = new LinkedHashMap<>();
        if (children == null) {
            return values;
        }
        for (DynamicRecord child : children) {
            if (child.getId() != null && !child.getId().isBlank()) {
                values.put(child.getId(), new LinkedHashMap<>(child.getValues()));
            }
        }
        return values;
    }

    private static Map<String, Object> mergedChildValues(DynamicRecord child, Map<String, Map<String, Object>> existingById) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (child.getId() != null && !child.getId().isBlank() && existingById.containsKey(child.getId())) {
            values.putAll(existingById.get(child.getId()));
        }
        values.putAll(child.getValues());
        return values;
    }
}
