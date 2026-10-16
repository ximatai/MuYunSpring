package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import java.lang.reflect.Field;
import java.util.*;

/** Resolves producer-owned declarations, never display text or arbitrary JSON. */
final class BusinessContentFields {
    private BusinessContentFields() { }
    private static final ClassValue<Map<String, ContentFieldDefinition>> STATIC = new ClassValue<>() {
        @Override protected Map<String, ContentFieldDefinition> computeValue(Class<?> type) {
            Map<String, ContentFieldDefinition> result = new LinkedHashMap<>();
            Set<String> keys = new HashSet<>();
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    var definition = net.ximatai.muyun.spring.dynamic.metadata.StaticContentFieldDefinitionCompiler.content(field);
                    if (!definition.enabled()) continue;
                    if (!keys.add(definition.key()) || result.putIfAbsent(field.getName(), definition) != null)
                        throw new IllegalArgumentException("Duplicate business content field identity: " + field);
                }
            }
            return Map.copyOf(result);
        }
    };

    static Map<String, ContentFieldDefinition> definitions(CrudAbility<?> ability, EntityContract record) {
        if (record instanceof DynamicRecord dynamic) {
            Map<String, ContentFieldDefinition> result = new LinkedHashMap<>();
            Set<String> keys = new HashSet<>();
            dynamic.getEntity().fields().stream().filter(field -> field.content().enabled()).forEach(field -> {
                if (!keys.add(field.content().key())) throw new IllegalArgumentException("Duplicate business content field identity");
                result.put(field.fieldName(), field.content());
            });
            return Map.copyOf(result);
        }
        return STATIC.get(ability.modelClass() == null ? record.getClass() : ability.modelClass());
    }

    static String entityKey(CrudAbility<?> ability, EntityContract record) {
        if (record instanceof DynamicRecord dynamic) return dynamic.getEntity().alias();
        Class<?> type = ability.modelClass() == null ? record.getClass() : ability.modelClass();
        Table table = type.getAnnotation(Table.class);
        if (table == null) throw new IllegalArgumentException("Business content requires a mapped model: " + type.getName());
        return table.name();
    }

    static String text(EntityContract record, String fieldName) {
        if (record instanceof DynamicRecord dynamic) {
            Object value = dynamic.getValue(fieldName);
            if (value != null && !(value instanceof String)) throw new IllegalArgumentException("Business content must be text");
            return (String) value;
        }
        for (Class<?> type = record.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                if (!field.trySetAccessible()) throw new IllegalStateException("Cannot access business content field");
                return (String) field.get(record);
            } catch (NoSuchFieldException ignored) {
                // Continue through inherited model properties.
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot read business content field", failure);
            }
        }
        throw new IllegalArgumentException("Unknown business content field: " + fieldName);
    }
}
