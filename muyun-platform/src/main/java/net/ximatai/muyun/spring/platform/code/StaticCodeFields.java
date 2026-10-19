package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.code.BusinessCode;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import java.lang.reflect.*;
import java.util.*;

/** Compiled model facts shared by rule validation, lifecycle adapters and configuration discovery. */
public final class StaticCodeFields {
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) {
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
            if (fields.values().stream().filter(field -> field.isAnnotationPresent(BusinessCode.class)
                    && field.getAnnotation(BusinessCode.class).primary()).count() > 1)
                throw new PlatformException("Only one PRIMARY business code field is allowed");
            return Collections.unmodifiableMap(fields);
        }
    };
    private StaticCodeFields() { }
    public static Map<String, BusinessCode> declarations(Class<?> type) {
        Map<String, BusinessCode> result = new LinkedHashMap<>();
        FIELDS.get(type).forEach((name, field) -> {
            if (field.isAnnotationPresent(BusinessCode.class)) result.put(name, field.getAnnotation(BusinessCode.class));
        });
        return Collections.unmodifiableMap(result);
    }
    public static Map<String, Object> values(Object record) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (record != null) FIELDS.get(record.getClass()).forEach((name, field) -> result.put(name, read(field, record)));
        return result;
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
