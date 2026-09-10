package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.common.formula.FormulaValueType;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.beans.Introspector;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import java.util.Optional;

/** Resolves static and dynamic reference targets through one platform boundary. */
public final class PlatformReferenceTargetResolver implements ReferenceTargetResolver {
    private final StaticAbilityCatalog staticAbilities;
    private final DynamicRecordRuntime dynamicRuntime;
    private final DynamicRecordService dynamicRecords;

    public PlatformReferenceTargetResolver(StaticAbilityCatalog staticAbilities,
                                           DynamicRecordRuntime dynamicRuntime) {
        this(staticAbilities, dynamicRuntime, null);
    }

    public PlatformReferenceTargetResolver(StaticAbilityCatalog staticAbilities,
                                           DynamicRecordRuntime dynamicRuntime,
                                           DynamicRecordService dynamicRecords) {
        this.staticAbilities = staticAbilities;
        this.dynamicRuntime = dynamicRuntime;
        this.dynamicRecords = dynamicRecords;
    }

    @Override
    public Optional<ReferenceAbility<?>> resolve(ReferenceTarget target) {
        if (staticAbilities != null) {
            Optional<ReferenceAbility<?>> resolved = staticAbilities.findReference(target);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        if (dynamicRecords != null) {
            Optional<ReferenceAbility<?>> scoped = dynamicRecords.referenceAbility(target);
            if (scoped.isPresent()) {
                return scoped;
            }
        }
        return dynamicRuntime == null ? Optional.empty() : dynamicRuntime.referenceAbility(target);
    }

    @Override
    public Optional<ReferencePlan> referencePlan(ReferenceTarget sourceTarget, String sourceField) {
        if (sourceTarget == null || sourceField == null || sourceField.isBlank()) {
            return Optional.empty();
        }
        if (staticAbilities != null) {
            Optional<ReferencePlan> staticPlan = staticAbilities.findByTarget(sourceTarget)
                    .flatMap(ability -> StaticReferenceResolver.plans(ability.modelClass()).stream()
                            .filter(plan -> sourceField.equals(plan.sourceField())).findFirst());
            if (staticPlan.isPresent()) {
                return staticPlan;
            }
        }
        return dynamicRuntime == null ? Optional.empty() : dynamicRuntime.referencePlan(sourceTarget, sourceField);
    }

    @Override
    public Optional<FormulaValueType> formulaFieldType(ReferenceTarget target, String fieldName) {
        if (target == null || fieldName == null || fieldName.isBlank()
                || StandardEntitySchema.fieldNames().contains(fieldName)) {
            return Optional.empty();
        }
        if (staticAbilities != null) {
            Optional<FormulaValueType> staticType = staticAbilities.findByTarget(target)
                    .flatMap(ability -> staticFormulaFieldType(ability, fieldName));
            if (staticType.isPresent()) return staticType;
        }
        if (dynamicRecords != null) {
            try {
                return dynamicRecords.entityDescriptor(target.moduleAlias(), target.entityAlias()).fields().stream()
                        .filter(field -> fieldName.equals(field.fieldName()))
                        .filter(field -> !field.encrypted() && !field.signed()
                                && (field.maskingPolicy() == null || field.maskingPolicy().isBlank())
                                && field.type() != net.ximatai.muyun.spring.dynamic.metadata.FieldType.JSON)
                        .map(field -> dynamicFormulaValueType(field.type()))
                        .findFirst();
            } catch (RuntimeException ignored) {
                // A target outside the active dynamic registry remains unavailable for formulas.
            }
        }
        return Optional.empty();
    }

    private static Optional<FormulaValueType> staticFormulaFieldType(CrudAbility<?> ability, String fieldName) {
        if (ability.modelClass() == null || ability instanceof net.ximatai.muyun.spring.ability.security.FieldProtectionAbility<?> protectedAbility
                && protectedAbility.fieldProtectionPlan().fields().stream().anyMatch(field -> fieldName.equals(field.fieldName()))) {
            return Optional.empty();
        }
        try {
            return java.util.Arrays.stream(Introspector.getBeanInfo(ability.modelClass(), Object.class).getPropertyDescriptors())
                    .filter(field -> fieldName.equals(field.getName()) && field.getReadMethod() != null)
                    .map(field -> staticFormulaValueType(field.getPropertyType())).findFirst();
        } catch (java.beans.IntrospectionException ignored) {
            return Optional.empty();
        }
    }

    private static FormulaValueType staticFormulaValueType(Class<?> type) {
        if (type == null) return FormulaValueType.ANY;
        if (type == String.class || CharSequence.class.isAssignableFrom(type) || type.isEnum()) return FormulaValueType.STRING;
        if (type == Integer.class || type == int.class || type == Short.class || type == short.class
                || type == Byte.class || type == byte.class) return FormulaValueType.INTEGER;
        if (type == Long.class || type == long.class) return FormulaValueType.LONG;
        if (type == BigDecimal.class || type == Double.class || type == double.class
                || type == Float.class || type == float.class) return FormulaValueType.DECIMAL;
        if (type == Boolean.class || type == boolean.class) return FormulaValueType.BOOLEAN;
        if (type == LocalDate.class || type == java.sql.Date.class) return FormulaValueType.DATE;
        if (type == Instant.class || type == LocalDateTime.class || type == OffsetDateTime.class
                || type == java.sql.Timestamp.class) return FormulaValueType.TIMESTAMP;
        return FormulaValueType.ANY;
    }

    private static FormulaValueType dynamicFormulaValueType(net.ximatai.muyun.spring.dynamic.metadata.FieldType type) {
        if (type == null) return FormulaValueType.ANY;
        return switch (type) {
            case STRING -> FormulaValueType.STRING;
            case TEXT -> FormulaValueType.TEXT;
            case INTEGER -> FormulaValueType.INTEGER;
            case LONG -> FormulaValueType.LONG;
            case BOOLEAN -> FormulaValueType.BOOLEAN;
            case TIMESTAMP, ZONED_TIMESTAMP -> FormulaValueType.TIMESTAMP;
            case DATE -> FormulaValueType.DATE;
            case DECIMAL -> FormulaValueType.DECIMAL;
            case JSON -> FormulaValueType.JSON;
        };
    }
}
