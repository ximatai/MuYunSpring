package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class FieldCompanionRules {
    private FieldCompanionRules() {
    }

    public static String zonedTimestampTimeZoneFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("zoned timestamp fieldName must not be blank");
        }
        return fieldName + "TimeZone";
    }

    public static String zonedTimestampTimeZoneColumnName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("zoned timestamp columnName must not be blank");
        }
        return columnName + "_timezone";
    }

    public static String signatureFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("signature owner fieldName must not be blank");
        }
        return fieldName + "Signature";
    }

    public static String signatureColumnName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("signature owner columnName must not be blank");
        }
        return columnName + "_signature";
    }

    public static List<FieldCompanionGroup> groups(EntityDefinition entity) {
        return entity.fields().stream()
                .map(FieldCompanionRules::group)
                .flatMap(List::stream)
                .toList();
    }

    public static List<FieldCompanionGroup> group(FieldDefinition owner) {
        if (!owner.isPhysical()) {
            return List.of();
        }
        java.util.ArrayList<FieldCompanionGroup> groups = new java.util.ArrayList<>();
        if (owner.type() == FieldType.ZONED_TIMESTAMP) {
            groups.add(new FieldCompanionGroup(
                    owner.fieldName(),
                    FieldCompanionKind.ZONED_TIMESTAMP,
                    List.of(new FieldCompanionDefinition(
                            owner.fieldName(),
                            zonedTimestampTimeZoneFieldName(owner.fieldName()),
                            zonedTimestampTimeZoneColumnName(owner.columnName()),
                            FieldType.STRING,
                            FieldCompanionRole.TIME_ZONE,
                            true,
                            true
                    ))
            ));
        }
        if (owner.protection().signatureMode().enabled()) {
            groups.add(new FieldCompanionGroup(
                    owner.fieldName(),
                    FieldCompanionKind.FIELD_PROTECTION,
                    List.of(new FieldCompanionDefinition(
                            owner.fieldName(),
                            signatureFieldName(owner.fieldName()),
                            signatureColumnName(owner.columnName()),
                            FieldType.STRING,
                            FieldCompanionRole.SIGNATURE,
                            false,
                            false
                    ))
            ));
        }
        return List.copyOf(groups);
    }

    public static Map<String, FieldCompanionDefinition> companionsByField(EntityDefinition entity) {
        return groups(entity).stream()
                .flatMap(group -> group.companions().stream())
                .collect(Collectors.toUnmodifiableMap(FieldCompanionDefinition::fieldName, Function.identity()));
    }

    public static Set<String> companionFieldNames(EntityDefinition entity) {
        return companionsByField(entity).keySet();
    }

    public static List<FieldDefinition> generatedFields(EntityDefinition entity) {
        return groups(entity).stream()
                .filter(group -> group.kind() == FieldCompanionKind.FIELD_PROTECTION)
                .flatMap(group -> group.companions().stream())
                .map(FieldCompanionRules::toGeneratedField)
                .toList();
    }

    public static List<FieldDefinition> recordFields(EntityDefinition entity) {
        java.util.ArrayList<FieldDefinition> fields = new java.util.ArrayList<>(entity.fields());
        fields.addAll(generatedFields(entity));
        return List.copyOf(fields);
    }

    public static Object normalizeCompanionValue(FieldCompanionDefinition companion, Object value) {
        if (companion.role() == FieldCompanionRole.TIME_ZONE) {
            return DynamicFieldValueSupport.normalizeTimeZone(value);
        }
        return DynamicFieldValueSupport.normalize(companion.type(), value);
    }

    public static void validateEntity(EntityDefinition entity) {
        Map<String, FieldDefinition> fieldsByName = entity.fields().stream()
                .collect(Collectors.toMap(FieldDefinition::fieldName, Function.identity()));
        for (FieldCompanionGroup group : groups(entity)) {
            for (FieldCompanionDefinition expected : group.companions()) {
                FieldDefinition actual = fieldsByName.get(expected.fieldName());
                if (group.kind() == FieldCompanionKind.FIELD_PROTECTION) {
                    if (actual != null) {
                        throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-protection-companion-must-not-be-declared-manually",
                    "field protection companion must not be declared manually: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(expected.fieldName()))));
                    }
                    continue;
                }
                if (actual == null) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-companion-is-missing",
                    "field companion is missing: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(expected.fieldName()))));
                }
                if (actual.type() != expected.type()) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-companion-type-mismatch",
                    "field companion type mismatch: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(expected.fieldName()))));
                }
                if (!expected.columnName().equals(actual.columnName())) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-companion-column-mismatch",
                    "field companion column mismatch: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(expected.fieldName()))));
                }
                FieldDefinition owner = fieldsByName.get(group.ownerField());
                if (owner != null && owner.isRequired() && !actual.isRequired()) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.required-field-requires-required-companion",
                    "required field requires required companion: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(expected.fieldName()))));
                }
            }
        }
    }

    public static void validateForInsert(EntityDefinition entity, Map<String, Object> values) {
        for (FieldCompanionGroup group : groups(entity)) {
            if (group.kind() == FieldCompanionKind.FIELD_PROTECTION) {
                continue;
            }
            if (values.get(group.ownerField()) == null) {
                continue;
            }
            for (FieldCompanionDefinition companion : group.companions()) {
                if (companion.requiredWhenOwnerPresent() && values.get(companion.fieldName()) == null) {
                    throw new IllegalArgumentException("field companion is missing: " + companion.fieldName());
                }
            }
        }
    }

    public static void validateForUpdate(EntityDefinition entity, Set<String> explicitFields) {
        for (FieldCompanionGroup group : groups(entity)) {
            if (group.kind() == FieldCompanionKind.FIELD_PROTECTION) {
                continue;
            }
            if (!explicitFields.contains(group.ownerField())) {
                continue;
            }
            for (FieldCompanionDefinition companion : group.companions()) {
                if (companion.requiredWhenOwnerUpdated() && !explicitFields.contains(companion.fieldName())) {
                    throw new IllegalArgumentException("field companion is missing: " + companion.fieldName());
                }
            }
        }
    }

    private static FieldDefinition toGeneratedField(FieldCompanionDefinition companion) {
        return new FieldDefinition(
                companion.fieldName(),
                companion.columnName(),
                companion.type(),
                companion.fieldName(),
                false,
                false,
                false,
                false,
                false,
                512,
                null,
                null,
                null,
                null,
                null,
                FieldBehaviorDefinition.DEFAULT,
                null,
                null,
                null
        );
    }
}
