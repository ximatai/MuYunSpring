package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Internal context assembly for the dynamic adapter of the shared code lifecycle.
 * Business callers save through the dynamic record facade rather than assembling this context.
 *
 * @hidden
 */
public class DynamicCodeContextBuilder {
    public Map<String, Object> recordContext(DynamicRecord record) {
        if (record == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> context = new LinkedHashMap<>(record.getValues());
        record.getPlatformValues().forEach(context::putIfAbsent);
        return context;
    }

    public Map<String, Object> mergedRecordContext(DynamicRecord before, DynamicRecord incoming) {
        LinkedHashMap<String, Object> context = new LinkedHashMap<>(recordContext(before));
        if (incoming != null) {
            context.putAll(incoming.getValues());
            incoming.getPlatformValues().forEach(context::putIfAbsent);
        }
        return context;
    }

    public Map<String, Object> childRecordContext(String parentEntityAlias,
                                                  String relationCode,
                                                  DynamicRecord parent,
                                                  String childEntityAlias,
                                                  DynamicRecord child) {
        LinkedHashMap<String, Object> context = new LinkedHashMap<>();
        if (parent != null) {
            Map<String, Object> parentContext = recordContext(parent);
            putPrefixedContext(context, parentEntityAlias, parentContext);
            putPrefixedContext(context, relationCode, parentContext);
        }
        if (child != null) {
            Map<String, Object> childContext = recordContext(child);
            context.putAll(childContext);
            putPrefixedContext(context, childEntityAlias, childContext);
        }
        return context;
    }

    public Map<String, Object> childMergedRecordContext(String parentEntityAlias,
                                                        String relationCode,
                                                        DynamicRecord parentBefore,
                                                        DynamicRecord parentIncoming,
                                                        String childEntityAlias,
                                                        DynamicRecord childBefore,
                                                        DynamicRecord childIncoming) {
        LinkedHashMap<String, Object> context = new LinkedHashMap<>();
        LinkedHashMap<String, Object> parentContext = new LinkedHashMap<>();
        if (parentBefore != null) {
            parentContext.putAll(recordContext(parentBefore));
        }
        if (parentIncoming != null) {
            parentContext.putAll(recordContext(parentIncoming));
        }
        putPrefixedContext(context, parentEntityAlias, parentContext);
        putPrefixedContext(context, relationCode, parentContext);

        LinkedHashMap<String, Object> childContext = new LinkedHashMap<>();
        if (childBefore != null) {
            childContext.putAll(recordContext(childBefore));
        }
        if (childIncoming != null) {
            childContext.putAll(recordContext(childIncoming));
        }
        context.putAll(childContext);
        putPrefixedContext(context, childEntityAlias, childContext);
        return context;
    }

    public Set<String> childChangedKeys(String parentEntityAlias,
                                        String relationCode,
                                        DynamicRecord parentIncoming,
                                        String childEntityAlias,
                                        DynamicRecord childIncoming) {
        java.util.LinkedHashSet<String> changedKeys = new java.util.LinkedHashSet<>();
        if (parentIncoming != null) {
            for (String field : parentIncoming.explicitFieldCodes()) {
                if (hasText(field)) {
                    addPrefixedKey(changedKeys, parentEntityAlias, field);
                    addPrefixedKey(changedKeys, relationCode, field);
                }
            }
        }
        if (childIncoming != null) {
            for (String field : childIncoming.explicitFieldCodes()) {
                if (hasText(field)) {
                    changedKeys.add(field);
                    addPrefixedKey(changedKeys, childEntityAlias, field);
                }
            }
        }
        return Set.copyOf(changedKeys);
    }

    private void putPrefixedContext(Map<String, Object> target, String prefix, Map<String, Object> source) {
        if (target == null || source == null || source.isEmpty() || !hasText(prefix)) {
            return;
        }
        source.forEach((key, value) -> {
            if (hasText(key)) {
                target.put(prefix + "." + key, value);
            }
        });
    }

    private void addPrefixedKey(Set<String> keys, String prefix, String field) {
        if (keys != null && hasText(prefix) && hasText(field)) {
            keys.add(prefix + "." + field);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
