package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaExecutionResult;
import net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition;
import net.ximatai.muyun.spring.common.formula.FormulaFieldPath;
import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRuleExecutionPlan;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import net.ximatai.muyun.spring.common.formula.FormulaValueType;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.ability.reference.FormulaReferenceContext;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes the constrained static-service projection of the shared formula engine.
 *
 * <p>The JavaBean contract exposes business properties without inferring annotation policy.
 * Standard platform fields and the explicit read-only title companions are excluded, and formula
 * output is converted completely before any property is written back to the entity.</p>
 */
final class MainRecordFormulaExecutor {
    /** Transient display companions from {@code StandardEntity}; they do not carry mutation facts. */
    private static final Set<String> READ_ONLY_COMPANION_FIELDS = Set.of("createdByTitle", "updatedByTitle");
    private static final Set<String> PROTECTED_FIELDS = protectedFields();

    private final FormulaEngine engine = new FormulaEngine();

    <T extends EntityContract> void execute(MainRecordFormulaAbility<T> ability, T entity) {
        List<FormulaRule> rules = ability.mainRecordFormulaRules();
        if (rules == null || rules.isEmpty()) {
            return;
        }
        Class<?> modelClass = ability.modelClass() == null ? entity.getClass() : ability.modelClass();
        Map<String, PropertyDescriptor> descriptors = descriptors(modelClass);
        List<FormulaFieldDefinition> mainFields = formulaFields(descriptors);
        List<FormulaFieldDefinition> fields = new java.util.ArrayList<>(mainFields);
        validateRules(rules);
        rejectProtectedCalculationTargets(rules);

        FormulaReferenceContext references;
        try {
            references = FormulaReferenceContext.compile(ReferenceTargets.of(ability), rules,
                    PlatformAbilityRuntime.referenceTargetResolver());
            fields.addAll(references.fields());
        } catch (IllegalArgumentException exception) {
            throw formulaFailure("FORMULA_REFERENCE_PATH_INVALID", exception.getMessage(), null, exception);
        }

        FormulaRuleExecutionPlan plan;
        try {
            plan = FormulaRuleExecutionPlan.forMainRecord(rules, fields);
        } catch (FormulaEvaluationException exception) {
            throw formulaFailure(exception.code(), exception.getMessage(), exception.fieldPath(), exception);
        }

        Map<String, Object> values = values(entity, descriptors, mainFields);
        FormulaExecutionResult result = plan.execute(engine, FormulaRuntimeData.typed(values, Map.of(), fields,
                references.paths(), references::resolve));
        if (result.report().hasErrors()) {
            throw new PlatformException("FORMULA_SAVE_VALIDATION_FAILED", 400,
                    "main-record formula validation failed: " + result.report().errors().stream()
                            .map(issue -> issue.code() + " [" + issue.ruleId() + "]: " + issue.message())
                            .collect(Collectors.joining("; ")),
                    net.ximatai.muyun.spring.common.exception.ErrorScope.empty(), List.of(),
                    Map.of("formulaIssues", result.report().errors()));
        }
        writeCalculatedValues(entity, descriptors, values, result.changedFields());
    }

    private void validateRules(List<FormulaRule> rules) {
        for (FormulaRule rule : rules) {
            if (rule == null || !rule.enabled()) {
                continue;
            }
            if (rule.phase() != FormulaRulePhase.BEFORE_SAVE) {
                throw formulaFailure("FORMULA_STATIC_PHASE_UNSUPPORTED",
                        "static main-record formulas only support BEFORE_SAVE [rule=" + rule.id() + "]", null, null);
            }
            if (rule.kind() != FormulaRuleKind.CALCULATION && rule.kind() != FormulaRuleKind.VALIDATION) {
                throw formulaFailure("FORMULA_STATIC_RULE_KIND_UNSUPPORTED",
                        "static main-record formulas only support calculation and validation [rule=" + rule.id() + "]",
                        null, null);
            }
        }
    }

    private void rejectProtectedCalculationTargets(List<FormulaRule> rules) {
        for (FormulaRule rule : rules) {
            if (rule == null || !rule.enabled() || rule.kind() != FormulaRuleKind.CALCULATION) {
                continue;
            }
            Set<String> targets;
            try {
                targets = new LinkedHashSet<>(engine.assignedFields(rule.expression()));
            } catch (FormulaEvaluationException exception) {
                throw formulaFailure(exception.code(), exception.getMessage(), exception.fieldPath(), exception);
            }
            if (rule.targetField() != null) {
                targets.add(rule.targetField());
            }
            for (String target : targets) {
                FormulaFieldPath path = FormulaFieldPath.parse(target);
                if (path.tableKey() != null) {
                    throw formulaFailure("FORMULA_STATIC_CHILD_SCOPE_UNSUPPORTED",
                            "static main-record formulas cannot write child fields: " + target + " [rule=" + rule.id() + "]",
                            target, null);
                }
                if (PROTECTED_FIELDS.contains(path.fieldName())) {
                    throw formulaFailure("FORMULA_STATIC_PROTECTED_FIELD",
                            "static main-record formula cannot write protected system field: " + path.fieldName()
                                    + " [rule=" + rule.id() + "]", path.dataIndex(), null);
                }
            }
        }
    }

    private Map<String, Object> values(Object entity,
                                        Map<String, PropertyDescriptor> descriptors,
                                        List<FormulaFieldDefinition> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FormulaFieldDefinition field : fields) {
            PropertyDescriptor descriptor = descriptors.get(field.fieldPath().fieldName());
            values.put(field.fieldPath().fieldName(), read(entity, descriptor));
        }
        return values;
    }

    private void writeCalculatedValues(Object entity,
                                       Map<String, PropertyDescriptor> descriptors,
                                       Map<String, Object> values,
                                       List<String> changedFields) {
        List<PendingWrite> pendingWrites = new java.util.ArrayList<>();
        for (String changedField : changedFields) {
            FormulaFieldPath path = FormulaFieldPath.parse(changedField);
            if (path.tableKey() != null) {
                throw formulaFailure("FORMULA_STATIC_CHILD_SCOPE_UNSUPPORTED",
                        "static main-record formulas cannot write child fields: " + changedField, changedField, null);
            }
            PropertyDescriptor descriptor = descriptors.get(path.fieldName());
            if (descriptor == null || descriptor.getWriteMethod() == null) {
                throw formulaFailure("FORMULA_FIELD_NOT_WRITABLE",
                        "static formula property is not writable: " + path.fieldName(), path.dataIndex(), null);
            }
            pendingWrites.add(new PendingWrite(descriptor,
                    adaptForProperty(values.get(path.fieldName()), descriptor.getPropertyType(), path.dataIndex())));
        }
        for (PendingWrite pendingWrite : pendingWrites) {
            write(entity, pendingWrite.descriptor(), pendingWrite.value());
        }
    }

    private List<FormulaFieldDefinition> formulaFields(Map<String, PropertyDescriptor> descriptors) {
        return descriptors.entrySet().stream()
                .filter(entry -> !PROTECTED_FIELDS.contains(entry.getKey()))
                .map(entry -> new FormulaFieldDefinition(FormulaFieldPath.parse(entry.getKey()),
                        formulaValueType(entry.getValue().getPropertyType()), false,
                        entry.getValue().getWriteMethod() != null))
                .toList();
    }

    private Map<String, PropertyDescriptor> descriptors(Class<?> modelClass) {
        try {
            return Arrays.stream(Introspector.getBeanInfo(modelClass, Object.class).getPropertyDescriptors())
                    .filter(descriptor -> descriptor.getReadMethod() != null)
                    .collect(Collectors.toMap(PropertyDescriptor::getName, descriptor -> descriptor,
                            (left, right) -> left, LinkedHashMap::new));
        } catch (IntrospectionException exception) {
            throw new PlatformException("FORMULA_STATIC_MODEL_INSPECTION_FAILED", 400,
                    "cannot inspect static formula model: " + modelClass.getName(), exception);
        }
    }

    private FormulaValueType formulaValueType(Class<?> type) {
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

    private Object read(Object entity, PropertyDescriptor descriptor) {
        try {
            return descriptor.getReadMethod().invoke(entity);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new PlatformException("FORMULA_STATIC_PROPERTY_READ_FAILED", 400,
                    "cannot read static formula property: " + descriptor.getName(), exception);
        }
    }

    private void write(Object entity, PropertyDescriptor descriptor, Object value) {
        try {
            descriptor.getWriteMethod().invoke(entity, value);
        } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException exception) {
            throw new PlatformException("FORMULA_STATIC_PROPERTY_WRITE_FAILED", 400,
                    "cannot write static formula property: " + descriptor.getName(), exception);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object adaptForProperty(Object value, Class<?> propertyType, String fieldPath) {
        if (value == null || propertyType == null || propertyType.isInstance(value)) return value;
        try {
            if (value instanceof Number number) {
                if (propertyType == Double.class || propertyType == double.class) return finiteDouble(number, fieldPath);
                if (propertyType == Float.class || propertyType == float.class) return finiteFloat(number, fieldPath);
                if (propertyType == Short.class || propertyType == short.class) return narrowShort(number, fieldPath);
                if (propertyType == Byte.class || propertyType == byte.class) return narrowByte(number, fieldPath);
            }
            if (value instanceof LocalDate date && propertyType == java.sql.Date.class) return java.sql.Date.valueOf(date);
            if (value instanceof Instant instant) {
                if (propertyType == LocalDateTime.class) return LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
                if (propertyType == OffsetDateTime.class) return instant.atOffset(java.time.ZoneOffset.UTC);
                if (propertyType == java.sql.Timestamp.class) return java.sql.Timestamp.from(instant);
            }
            if (value instanceof String text && propertyType.isEnum()) {
                return Enum.valueOf((Class<? extends Enum>) propertyType, text);
            }
            return value;
        } catch (PlatformException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw formulaFailure("FORMULA_STATIC_PROPERTY_CONVERSION_FAILED",
                    "cannot convert static formula value for property: " + fieldPath, fieldPath, exception);
        }
    }

    private byte narrowByte(Number number, String fieldPath) {
        long value = exactIntegral(number, fieldPath);
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
            throw narrowingFailure(fieldPath, "Byte", number);
        }
        return (byte) value;
    }

    private short narrowShort(Number number, String fieldPath) {
        long value = exactIntegral(number, fieldPath);
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw narrowingFailure(fieldPath, "Short", number);
        }
        return (short) value;
    }

    private long exactIntegral(Number number, String fieldPath) {
        try {
            if (number instanceof BigInteger integer) return integer.longValueExact();
            if (number instanceof BigDecimal decimal) return decimal.toBigIntegerExact().longValueExact();
            if (number instanceof Double value) {
                if (!Double.isFinite(value) || value != Math.rint(value)) throw narrowingFailure(fieldPath, "integral", number);
                return BigDecimal.valueOf(value).longValueExact();
            }
            if (number instanceof Float value) {
                if (!Float.isFinite(value) || value != Math.rint(value)) throw narrowingFailure(fieldPath, "integral", number);
                return BigDecimal.valueOf(value.doubleValue()).longValueExact();
            }
            return number.longValue();
        } catch (ArithmeticException exception) {
            throw narrowingFailure(fieldPath, "integral", number, exception);
        }
    }

    private double finiteDouble(Number number, String fieldPath) {
        double value = decimal(number, fieldPath).doubleValue();
        if (!Double.isFinite(value)) throw narrowingFailure(fieldPath, "Double", number);
        return value;
    }

    private float finiteFloat(Number number, String fieldPath) {
        BigDecimal decimal = decimal(number, fieldPath);
        BigDecimal absolute = decimal.abs();
        if (absolute.compareTo(BigDecimal.valueOf(Float.MAX_VALUE)) > 0
                || (decimal.signum() != 0 && absolute.compareTo(BigDecimal.valueOf(Float.MIN_VALUE)) < 0)) {
            throw narrowingFailure(fieldPath, "Float", number);
        }
        float value = decimal.floatValue();
        if (!Float.isFinite(value)) throw narrowingFailure(fieldPath, "Float", number);
        return value;
    }

    private BigDecimal decimal(Number number, String fieldPath) {
        try {
            if (number instanceof BigDecimal decimal) return decimal;
            if (number instanceof BigInteger integer) return new BigDecimal(integer);
            if (number instanceof Double value && !Double.isFinite(value)) throw narrowingFailure(fieldPath, "decimal", number);
            if (number instanceof Float value && !Float.isFinite(value)) throw narrowingFailure(fieldPath, "decimal", number);
            return new BigDecimal(number.toString());
        } catch (NumberFormatException exception) {
            throw narrowingFailure(fieldPath, "decimal", number, exception);
        }
    }

    private PlatformException narrowingFailure(String fieldPath, String targetType, Number value) {
        return narrowingFailure(fieldPath, targetType, value, null);
    }

    private PlatformException narrowingFailure(String fieldPath, String targetType, Number value, Throwable cause) {
        return formulaFailure("FORMULA_STATIC_NUMERIC_NARROWING_FAILED",
                "static formula value cannot be represented as " + targetType + ": " + value,
                fieldPath, cause);
    }

    private static Set<String> protectedFields() {
        LinkedHashSet<String> fields = new LinkedHashSet<>(StandardEntitySchema.fieldNames());
        fields.addAll(READ_ONLY_COMPANION_FIELDS);
        return Set.copyOf(fields);
    }

    private PlatformException formulaFailure(String code, String message, String fieldPath, Throwable cause) {
        Map<String, Object> details = fieldPath == null ? Map.of() : Map.of("fieldPath", fieldPath);
        return cause == null
                ? new PlatformException(code, 400, message,
                net.ximatai.muyun.spring.common.exception.ErrorScope.empty(), List.of(), details)
                : new PlatformException(code, 400, message, cause,
                net.ximatai.muyun.spring.common.exception.ErrorScope.empty(), List.of(), details);
    }

    private record PendingWrite(PropertyDescriptor descriptor, Object value) {
    }
}
