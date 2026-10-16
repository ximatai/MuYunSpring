package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.security.*;
import java.lang.reflect.Field;

/** Compiles the same content contract used by dynamic metadata, before page/runtime delivery. */
public final class StaticContentFieldDefinitionCompiler {
    private StaticContentFieldDefinitionCompiler() { }
    public static FieldDefinition compile(FieldDefinition definition, Field field) {
        return definition.content(content(field));
    }
    public static ContentFieldDefinition content(Field field) {
        BusinessContent annotation = field.getAnnotation(BusinessContent.class);
        if (annotation == null || !annotation.value().enabled()) return ContentFieldDefinition.NONE;
        Column column = field.getAnnotation(Column.class);
        if (field.getType() != String.class || column == null
                || column.type() != ColumnType.VARCHAR && column.type() != ColumnType.TEXT
                || field.isAnnotationPresent(EncryptedField.class) || field.isAnnotationPresent(MaskedField.class)
                || field.isAnnotationPresent(SignedField.class)
                || field.isAnnotationPresent(net.ximatai.muyun.spring.common.model.file.FileReference.class)
                || field.isAnnotationPresent(net.ximatai.muyun.spring.ability.reference.ReferenceTo.class)
                || field.isAnnotationPresent(net.ximatai.muyun.spring.common.option.DictionaryField.class))
            throw new IllegalArgumentException("Business content requires an unprotected physical text field: " + field);
        return new ContentFieldDefinition(annotation.key().isBlank() ? field.getName() : annotation.key(), annotation.value());
    }
}
