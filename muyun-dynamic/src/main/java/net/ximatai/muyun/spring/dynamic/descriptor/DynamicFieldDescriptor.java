package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRules;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldOptionLoadDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldStorageForm;
import net.ximatai.muyun.spring.dynamic.metadata.FieldTemporalSemantics;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;

import java.util.List;

public record DynamicFieldDescriptor(
        String fieldName,
        FieldType type,
        FieldTemporalSemantics temporalSemantics,
        String title,
        FieldStorageForm storageForm,
        boolean required,
        boolean unique,
        boolean indexed,
        boolean sortable,
        boolean titleField,
        Integer length,
        Integer precision,
        Integer scale,
        OptionBinding optionBinding,
        OptionSelectionMode selectionMode,
        FieldOptionLoadDefinition optionLoad,
        DynamicReferenceDescriptor reference,
        List<DynamicFieldCompanionDescriptor> companions,
        DynamicFieldQueryDescriptor query,
        String defaultValue,
        String validationRegex,
        boolean copyable,
        boolean writeProtected,
        boolean encrypted,
        boolean signed,
        String maskingPolicy,
        FieldMeasureUnitDefinition measureUnit,
        FieldWriteRules writeRules
) {
    public DynamicFieldDescriptor(
        String fieldName,
        FieldType type,
        FieldTemporalSemantics temporalSemantics,
        String title,
        FieldStorageForm storageForm,
        boolean required,
        boolean unique,
        boolean indexed,
        boolean sortable,
        boolean titleField,
        Integer length,
        Integer precision,
        Integer scale,
        OptionBinding optionBinding,
        OptionSelectionMode selectionMode,
        FieldOptionLoadDefinition optionLoad,
        DynamicReferenceDescriptor reference,
        List<DynamicFieldCompanionDescriptor> companions,
        DynamicFieldQueryDescriptor query,
        String defaultValue,
        String validationRegex,
        boolean copyable,
        boolean writeProtected,
        boolean encrypted,
        boolean signed,
        String maskingPolicy,
        FieldMeasureUnitDefinition measureUnit
    ) {
        this(fieldName, type, temporalSemantics, title, storageForm, required, unique, indexed, sortable, titleField,
                length, precision, scale, optionBinding, selectionMode, optionLoad, reference, companions, query,
                defaultValue, validationRegex, copyable, writeProtected, encrypted, signed, maskingPolicy, measureUnit,
                FieldWriteRules.NONE);
    }

    public DynamicFieldDescriptor {
        writeRules = writeRules == null ? FieldWriteRules.NONE : writeRules;
        temporalSemantics = temporalSemantics == null
                ? (type == null ? FieldTemporalSemantics.NONE : type.temporalSemantics())
                : temporalSemantics;
        companions = companions == null ? List.of() : List.copyOf(companions);
    }

    public static DynamicFieldDescriptor from(FieldDefinition field) {
        return new DynamicFieldDescriptor(
                field.fieldName(),
                field.type(),
                field.type().temporalSemantics(),
                field.name(),
                field.storageForm(),
                field.isRequired(),
                field.isUnique(),
                field.isIndexed(),
                field.isSortable(),
                field.isTitle(),
                field.length(),
                field.precision(),
                field.scale(),
                field.optionBinding(),
                field.dictionaryBinding() == null ? null : field.dictionaryBinding().selectionMode(),
                field.optionLoad(),
                null,
                companions(field),
                DynamicFieldQueryDescriptor.from(field.queryDefinition()),
                field.behavior().defaultValue(),
                field.behavior().validationRegex(),
                field.behavior().copyable(),
                field.behavior().writeProtected(),
                field.protection().encryptionMode().enabled(),
                field.protection().signatureMode().enabled(),
                field.protection().maskingPolicy().enabled() ? field.protection().maskingPolicy().name() : null,
                field.measureUnit(),
                field.resolvedWriteRules()
        );
    }

    public static DynamicFieldDescriptor from(FieldDefinition field, DynamicReferenceDescriptor reference) {
        DynamicFieldDescriptor descriptor = from(field);
        return new DynamicFieldDescriptor(
                descriptor.fieldName(),
                descriptor.type(),
                descriptor.temporalSemantics(),
                descriptor.title(),
                descriptor.storageForm(),
                descriptor.required(),
                descriptor.unique(),
                descriptor.indexed(),
                descriptor.sortable(),
                descriptor.titleField(),
                descriptor.length(),
                descriptor.precision(),
                descriptor.scale(),
                descriptor.optionBinding(),
                descriptor.selectionMode(),
                descriptor.optionLoad(),
                reference,
                descriptor.companions(),
                descriptor.query(),
                descriptor.defaultValue(),
                descriptor.validationRegex(),
                descriptor.copyable(),
                descriptor.writeProtected(),
                descriptor.encrypted(),
                descriptor.signed(),
                descriptor.maskingPolicy(),
                descriptor.measureUnit(),
                descriptor.writeRules()
        );
    }

    private static List<DynamicFieldCompanionDescriptor> companions(FieldDefinition field) {
        return FieldCompanionRules.group(field).stream()
                .flatMap(group -> group.companions().stream()
                        .map(companion -> DynamicFieldCompanionDescriptor.from(group.kind(), companion)))
                .toList();
    }
}
