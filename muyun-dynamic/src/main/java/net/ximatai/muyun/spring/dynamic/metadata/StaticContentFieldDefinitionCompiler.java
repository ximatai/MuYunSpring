package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.security.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import net.ximatai.muyun.spring.common.schema.*;

/** Compiles the same content contract used by dynamic metadata, before page/runtime delivery. */
public final class StaticContentFieldDefinitionCompiler {
    private StaticContentFieldDefinitionCompiler() { }
    private static final ClassValue<Map<String, ContentFieldDefinition>> CACHE = new ClassValue<>() {
        @Override protected Map<String, ContentFieldDefinition> computeValue(Class<?> type) {
            Map<String, Field> fields = new LinkedHashMap<>();
            List<Class<?>> owners = new ArrayList<>();
            for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
                owners.add(owner);
                for (Field field : owner.getDeclaredFields()) fields.putIfAbsent(field.getName(), field);
            }
            Map<String, ContentFieldDefinition> result = new LinkedHashMap<>();
            Set<String> declared = new HashSet<>();
            Set<String> keys = new HashSet<>();
            for (Class<?> owner : owners.reversed()) {
                for (Field field : owner.getDeclaredFields()) {
                    for (BusinessContent annotation : field.getDeclaredAnnotationsByType(BusinessContent.class)) {
                        if (!annotation.field().isEmpty() || !field.equals(fields.get(field.getName())))
                            throw new IllegalArgumentException("Business content field must be local and unshadowed: " + field);
                        bind(result, declared, keys, field, annotation);
                    }
                }
                for (BusinessContent annotation : owner.getDeclaredAnnotationsByType(BusinessContent.class)) {
                    Field field = fields.get(annotation.field());
                    if (field == null || !field.getDeclaringClass().isAssignableFrom(owner))
                        throw new IllegalArgumentException("Unknown or shadowed business content field: " + owner.getName() + "." + annotation.field());
                    bind(result, declared, keys, field, annotation);
                }
            }
            return Collections.unmodifiableMap(result);
        }
    };

    /** One validated declaration map for storage capture and all static projections. */
    public static Map<String, ContentFieldDefinition> resolve(Class<?> modelClass) {
        return CACHE.get(Objects.requireNonNull(modelClass, "modelClass"));
    }

    private static void bind(Map<String, ContentFieldDefinition> result, Set<String> declared, Set<String> keys,
                             Field field, BusinessContent annotation) {
        if (!declared.add(field.getName())) throw new IllegalArgumentException("Duplicate business content declaration: " + field);
        var definition = content(field, annotation);
        if (!definition.enabled()) return;
        if (!keys.add(definition.key())) throw new IllegalArgumentException("Duplicate business content field identity: " + definition.key());
        result.put(field.getName(), definition);
    }

    public static FieldDefinition compile(FieldDefinition definition, Field field) {
        return definition.content(content(field));
    }
    public static ContentFieldDefinition content(Field field) {
        var annotations = field.getDeclaredAnnotationsByType(BusinessContent.class);
        if (annotations.length == 0) return ContentFieldDefinition.NONE;
        if (annotations.length != 1 || !annotations[0].field().isEmpty())
            throw new IllegalArgumentException("Business content field requires one local declaration: " + field);
        return content(field, annotations[0]);
    }

    private static ContentFieldDefinition content(Field field, BusinessContent annotation) {
        if (!annotation.value().enabled()) return ContentFieldDefinition.NONE;
        Column column = field.getAnnotation(Column.class);
        if (field.getType() != String.class || column == null
                || Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())
                || Modifier.isFinal(field.getModifiers()) || managed(field, column)
                || column.type() != ColumnType.VARCHAR && column.type() != ColumnType.TEXT
                || field.isAnnotationPresent(EncryptedField.class) || field.isAnnotationPresent(MaskedField.class)
                || field.isAnnotationPresent(SignedField.class)
                || field.isAnnotationPresent(net.ximatai.muyun.spring.common.model.file.FileReference.class)
                || field.isAnnotationPresent(net.ximatai.muyun.spring.ability.reference.ReferenceTo.class)
                || field.isAnnotationPresent(net.ximatai.muyun.spring.common.option.DictionaryField.class))
            throw new IllegalArgumentException("Business content requires an unprotected physical text field: " + field);
        return new ContentFieldDefinition(annotation.key().isBlank() ? field.getName() : annotation.key(), annotation.value());
    }

    private static boolean managed(Field field, Column column) {
        return StandardEntitySchema.fieldNames().contains(field.getName()) || StandardEntitySchema.columnNames().contains(column.name())
                || PlatformDataScopeSchema.fieldNames().contains(field.getName()) || PlatformDataScopeSchema.columnNames().contains(column.name())
                || PlatformAbilityFields.TREE_PARENT_FIELD.equals(field.getName()) || PlatformAbilityFields.TREE_PARENT_COLUMN.equals(column.name());
    }
}
