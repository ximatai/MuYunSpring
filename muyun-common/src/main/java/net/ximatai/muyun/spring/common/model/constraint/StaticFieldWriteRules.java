package net.ximatai.muyun.spring.common.model.constraint;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compiles model declarations once for both static writes and metadata projections. */
public final class StaticFieldWriteRules {
    private StaticFieldWriteRules() {}

    private static final ClassValue<Map<String, Binding>> CACHE = new ClassValue<>() {
        @Override protected Map<String, Binding> computeValue(Class<?> type) {
            List<Class<?>> hierarchy = new ArrayList<>();
            Map<String, Field> fields = new LinkedHashMap<>();
            for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
                hierarchy.add(owner);
                for (Field field : owner.getDeclaredFields()) fields.putIfAbsent(field.getName(), field);
            }
            Map<String, Binding> bindings = new LinkedHashMap<>();
            // Child declarations replace only the declared facet; normalization and required phases are independent.
            for (Class<?> owner : hierarchy.reversed()) {
                Set<String> requiredFields = new HashSet<>();
                Set<String> normalizedFields = new HashSet<>();
                for (Field field : owner.getDeclaredFields()) {
                    for (Required rule : field.getDeclaredAnnotationsByType(Required.class)) {
                        requireLocal(rule.fields(), field);
                        requireUnshadowed(fields, field);
                        required(bindings, field, rule, requiredFields);
                    }
                    for (NormalizeText rule : field.getDeclaredAnnotationsByType(NormalizeText.class)) {
                        requireLocal(rule.fields(), field);
                        requireUnshadowed(fields, field);
                        normalize(bindings, field, rule.value(), normalizedFields);
                    }
                }
                for (Required rule : owner.getDeclaredAnnotationsByType(Required.class)) {
                    for (String name : requireNames(rule.fields(), owner)) {
                        required(bindings, namedField(fields, owner, name), rule, requiredFields);
                    }
                }
                for (NormalizeText rule : owner.getDeclaredAnnotationsByType(NormalizeText.class)) {
                    for (String name : requireNames(rule.fields(), owner)) {
                        normalize(bindings, namedField(fields, owner, name), rule.value(), normalizedFields);
                    }
                }
            }
            return Collections.unmodifiableMap(bindings);
        }
    };

    private static void required(Map<String, Binding> bindings, Field field, Required rule, Set<String> declared) {
        unique(declared, field, "required");
        Set<WriteOperation> operations = new HashSet<>(Arrays.asList(rule.on()));
        if (operations.isEmpty()) throw new IllegalArgumentException("required operations must not be empty: " + field.getName());
        FieldWriteRules previous = bindingRules(bindings, field);
        bind(bindings, field, new FieldWriteRules(operations.contains(WriteOperation.INSERT),
                operations.contains(WriteOperation.UPDATE), previous.textNormalization()));
    }

    private static void normalize(Map<String, Binding> bindings, Field field, TextNormalization normalization,
                                  Set<String> declared) {
        unique(declared, field, "normalization");
        if (field.getType() != String.class) {
            throw new IllegalArgumentException("text normalization requires string field: " + field.getName());
        }
        FieldWriteRules previous = bindingRules(bindings, field);
        bind(bindings, field, new FieldWriteRules(previous.requiredOnInsert(), previous.requiredOnUpdate(), normalization));
    }

    private static FieldWriteRules bindingRules(Map<String, Binding> bindings, Field field) {
        Binding previous = bindings.get(field.getName());
        return previous == null ? FieldWriteRules.NONE : previous.rules();
    }

    private static void bind(Map<String, Binding> bindings, Field field, FieldWriteRules rules) {
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                || Modifier.isTransient(field.getModifiers())) {
            throw new IllegalArgumentException("write rules require a writable field: " + field.getName());
        }
        field.setAccessible(true);
        bindings.put(field.getName(), new Binding(field, rules));
    }

    private static void unique(Set<String> names, Field field, String kind) {
        if (!names.add(field.getName())) {
            throw new IllegalArgumentException("duplicate " + kind + " declaration: " + field.getName());
        }
    }

    private static String[] requireNames(String[] names, Class<?> owner) {
        if (names.length == 0) throw new IllegalArgumentException("type write rules require field names: " + owner.getName());
        return names;
    }

    private static void requireLocal(String[] names, Field field) {
        if (names.length != 0) throw new IllegalArgumentException("field write rules cannot name other fields: " + field.getName());
    }

    private static Field namedField(Map<String, Field> fields, Class<?> owner, String name) {
        Field field = fields.get(name);
        if (field == null || !field.getDeclaringClass().isAssignableFrom(owner)) {
            throw new IllegalArgumentException("unknown or shadowed write rule field: " + owner.getName() + "." + name);
        }
        return field;
    }

    private static void requireUnshadowed(Map<String, Field> fields, Field field) {
        if (!field.equals(fields.get(field.getName()))) {
            throw new IllegalArgumentException("write rule field must not be shadowed: " + field.getName());
        }
    }

    public static Map<String, Binding> resolve(Class<?> type) {
        return type == null ? Map.of() : CACHE.get(type);
    }

    /** Shared normalization for standard CRUD and internal writers that own their lifecycle. */
    public static void normalize(Class<?> type, Object record) {
        resolve(type).values().forEach(binding -> {
            Object value = binding.read(record);
            Object normalized = binding.rules().normalize(value);
            if (!Objects.equals(value, normalized)) binding.write(record, normalized);
        });
    }

    /** Shares validation with standard CRUD without running a second lifecycle or changing values. */
    public static void validate(Class<?> type, Object record, WriteOperation operation) {
        Objects.requireNonNull(operation, "operation");
        resolve(type).forEach((name, binding) -> binding.rules().validate(name, binding.read(record), operation == WriteOperation.UPDATE));
    }

    public record Binding(Field field, FieldWriteRules rules) {
        public Object read(Object record) {
            try { return field.get(record); }
            catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }
        public void write(Object record, Object value) {
            try { field.set(record, value); }
            catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }
    }
}
