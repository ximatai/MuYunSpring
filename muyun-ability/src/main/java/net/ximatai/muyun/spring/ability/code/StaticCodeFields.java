package net.ximatai.muyun.spring.ability.code;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import java.lang.reflect.*;
import java.util.*;

/** Compiled model facts shared by rule validation, lifecycle adapters and configuration discovery. */
public final class StaticCodeFields {
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) {
            boolean coded = false;
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
                coded |= Arrays.stream(current.getDeclaredFields()).anyMatch(field -> field.isAnnotationPresent(BusinessCode.class));
            if (!coded) return Map.of();
            Map<String, Field> fields = new LinkedHashMap<>();
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.isAnnotationPresent(BusinessCode.class) && (Modifier.isStatic(field.getModifiers())
                            || Modifier.isTransient(field.getModifiers())))
                        throw new PlatformException("Business code requires a persisted string field: " + field.getName());
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                    if (fields.putIfAbsent(field.getName(), field) != null)
                        throw new PlatformException("Shadowed business field: " + field.getName());
                    field.setAccessible(true);
                    if (field.isAnnotationPresent(BusinessCode.class) && (Modifier.isFinal(field.getModifiers()) || field.getType() != String.class
                            || !field.isAnnotationPresent(net.ximatai.muyun.database.core.annotation.Column.class)))
                        throw new PlatformException("Business code requires a persisted string field: " + field.getName());
                }
            }
            Set<String> protectedNames = net.ximatai.muyun.spring.ability.security.StaticFieldProtectionResolver.resolve(type).fields().stream()
                    .map(net.ximatai.muyun.spring.ability.security.ProtectedFieldAccessor::fieldName).collect(java.util.stream.Collectors.toSet());
            var writeRules = net.ximatai.muyun.spring.common.model.constraint.StaticFieldWriteRules.resolve(type);
            for (Field field : fields.values()) if (field.isAnnotationPresent(BusinessCode.class)) {
                var column = field.getAnnotation(net.ximatai.muyun.database.core.annotation.Column.class);
                var write = writeRules.get(field.getName());
                if (field.getAnnotation(BusinessCode.class).trigger() == CodeTrigger.EXPLICIT
                        && (!column.nullable() || write != null && (write.rules().requiredOnInsert() || write.rules().requiredOnUpdate())))
                    throw new PlatformException("EXPLICIT business code must allow unnumbered drafts: " + field.getName());
                if (net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy.find(field.getName()) != null
                        || column.type() != net.ximatai.muyun.database.core.builder.ColumnType.VARCHAR || protectedNames.contains(field.getName())
                        || field.isAnnotationPresent(net.ximatai.muyun.spring.ability.reference.ReferenceTo.class))
                    throw new PlatformException("Business code requires an unprotected business VARCHAR field: " + field.getName());
            }
            if (fields.values().stream().filter(field -> field.isAnnotationPresent(BusinessCode.class)
                    && field.getAnnotation(BusinessCode.class).primary()).count() > 1)
                throw new PlatformException("Only one PRIMARY business code field is allowed");
            return Collections.unmodifiableMap(fields);
        }
    };
    private static final ClassValue<Map<String, Field>> PERSISTED = new ClassValue<>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> result = new LinkedHashMap<>();
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
                for (Field field : current.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())
                        && !Modifier.isTransient(field.getModifiers()) && field.isAnnotationPresent(net.ximatai.muyun.database.core.annotation.Column.class)) {
                    field.setAccessible(true); result.putIfAbsent(field.getName(), field);
                }
            return Collections.unmodifiableMap(result);
        }
    };
    private StaticCodeFields() { }
    public static Map<String, Field> persistedFields(Class<?> type) {
        FIELDS.get(type); // Validate declarations before exposing their physical source facts.
        return PERSISTED.get(type);
    }
    public static Map<String, BusinessCode> declarations(Class<?> type) {
        Map<String, BusinessCode> result = new LinkedHashMap<>();
        FIELDS.get(type).forEach((name, field) -> {
            if (field.isAnnotationPresent(BusinessCode.class)) result.put(name, field.getAnnotation(BusinessCode.class));
        });
        return Collections.unmodifiableMap(result);
    }
    public static Map<String, Object> values(Object record) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (record != null) persistedFields(record.getClass()).forEach((name, field) -> result.put(name, read(field, record)));
        return result;
    }
    public static String normalizeCode(Class<?> type, String field, String value) {
        var binding = net.ximatai.muyun.spring.common.model.constraint.StaticFieldWriteRules.resolve(type).get(field);
        String normalized = binding == null ? value : (String) binding.rules().normalize(value);
        int declaredLength = FIELDS.get(type).get(field).getAnnotation(net.ximatai.muyun.database.core.annotation.Column.class).length();
        int limit = declaredLength > 0 ? Math.min(declaredLength, CodeFieldDefinition.MAX_CODE_LENGTH) : CodeFieldDefinition.MAX_CODE_LENGTH;
        if (normalized != null && normalized.length() > limit) throw new PlatformException("Business code exceeds the target field length: " + limit);
        return normalized;
    }
    public static void write(Object record, String name, Object value) {
        Field field = FIELDS.get(record.getClass()).get(name);
        if (field == null || !field.isAnnotationPresent(BusinessCode.class))
            throw new PlatformException("Undeclared business code field: " + name);
        try { field.set(record, value); }
        catch (IllegalAccessException failure) { throw new PlatformException("Cannot assign business code: " + name, failure); }
    }
    private static Object read(Field field, Object record) {
        try { return field.get(record); }
        catch (IllegalAccessException failure) { throw new PlatformException("Cannot read business field: " + field.getName(), failure); }
    }
}
