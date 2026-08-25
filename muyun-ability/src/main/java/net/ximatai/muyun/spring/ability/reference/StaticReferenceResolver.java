package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValue;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueCase;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueCasePlan;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValuePlan;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class StaticReferenceResolver {
    private static final Map<Class<?>, List<ReferenceRule>> RULES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<ReferenceLoadPath>> LOAD_PATHS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<ReferenceSummaryPlan>> SUMMARY_PLANS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<DiscriminatedValuePlan>> DISCRIMINATED_VALUES = new ConcurrentHashMap<>();

    private StaticReferenceResolver() {
    }

    public static Map<ReferenceTarget, Set<String>> collect(Object record) {
        return record == null ? Map.of() : collect(record.getClass(), record);
    }

    public static Map<ReferenceTarget, Set<String>> collect(Class<?> modelClass, Object record) {
        if (modelClass == null || record == null) {
            return Map.of();
        }
        if (!modelClass.isInstance(record)) {
            throw new PlatformException("reference source type mismatch: expected "
                    + modelClass.getName() + ", actual " + record.getClass().getName());
        }
        Map<ReferenceTarget, Set<String>> ids = new LinkedHashMap<>();
        for (ReferenceRule rule : rules(modelClass)) {
            List<String> values = rule.values(record);
            if (values.isEmpty()) {
                continue;
            }
            ids.computeIfAbsent(rule.target(), ignored -> new LinkedHashSet<>()).addAll(values);
        }
        Map<ReferenceTarget, Set<String>> copy = new LinkedHashMap<>();
        ids.forEach((target, values) -> copy.put(target, Collections.unmodifiableSet(new LinkedHashSet<>(values))));
        return Collections.unmodifiableMap(copy);
    }

    public static List<ReferenceRule> rules(Class<?> modelClass) {
        if (modelClass == null) {
            return List.of();
        }
        return RULES.computeIfAbsent(modelClass, StaticReferenceResolver::loadRules);
    }

    public static List<ReferencePlan> plans(Class<?> modelClass) {
        return rules(modelClass).stream()
                .map(ReferenceRule::plan)
                .toList();
    }

    public static List<ReferenceLoadPath> loadPaths(Class<?> modelClass) {
        if (modelClass == null) {
            return List.of();
        }
        return LOAD_PATHS.computeIfAbsent(modelClass, StaticReferenceResolver::compileLoadPaths);
    }

    public static List<ReferenceSummaryPlan> summaryPlans(Class<?> modelClass) {
        if (modelClass == null) {
            return List.of();
        }
        return SUMMARY_PLANS.computeIfAbsent(modelClass, StaticReferenceResolver::compileSummaryPlans);
    }

    /** Compiles field-local discriminator declarations without treating every branch as a normal reference. */
    public static List<DiscriminatedValuePlan> discriminatedValuePlans(Class<?> modelClass) {
        if (modelClass == null) return List.of();
        return DISCRIMINATED_VALUES.computeIfAbsent(modelClass, StaticReferenceResolver::compileDiscriminatedValuePlans);
    }

    public static List<String> values(Object record, ReferencePlan plan) {
        if (record == null || plan == null) {
            return List.of();
        }
        return rules(record.getClass()).stream()
                .filter(rule -> rule.plan().equals(plan))
                .findFirst()
                .map(rule -> rule.values(record))
                .orElseGet(() -> plan.normalizeValues(readByFieldName(record, plan.sourceField())));
    }

    public static void writeLoadedValue(Object record, String outputField, Object value) {
        if (record == null || outputField == null || outputField.isBlank()) {
            return;
        }
        writeByFieldName(record, outputField, value);
    }

    /** Reads a declared model field for source-independent inverse association grouping. */
    public static Object readLoadedValue(Object record, String fieldName) {
        return record == null || fieldName == null || fieldName.isBlank() ? null : readByFieldName(record, fieldName);
    }

    public static void requireReadableField(Class<?> modelClass, String fieldName, String purpose) {
        if (modelClass == null || fieldName == null || fieldName.isBlank()) {
            throw new PlatformException(purpose + " field must not be blank");
        }
        readField(modelClass, fieldName);
    }

    static void clearCacheForTests() {
        RULES.clear();
        LOAD_PATHS.clear();
        SUMMARY_PLANS.clear();
        DISCRIMINATED_VALUES.clear();
    }

    private static List<ReferenceRule> loadRules(Class<?> modelClass) {
        LinkedHashMap<String, Field> fields = fields(modelClass);
        LinkedHashMap<String, ReferenceRule> rules = new LinkedHashMap<>();
        for (Field field : fields.values()) {
                ReferenceTo referenceTo = field.getAnnotation(ReferenceTo.class);
                if (referenceTo == null) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (RuntimeException e) {
                    throw new PlatformException("cannot access reference field: "
                            + modelClass.getName() + "." + field.getName(), e);
                }
                rules.putIfAbsent(field.getName(), new ReferenceRule(field, referenceToPlan(field, referenceTo)));
        }
        Set<String> outputFields = new LinkedHashSet<>();
        for (Field output : fields.values()) {
            ReferenceLoad load = output.getAnnotation(ReferenceLoad.class);
            if (load == null) {
                continue;
            }
            ReferenceRule source = rules.get(load.source());
            if (source == null) {
                throw new PlatformException("ReferenceLoad source must declare @ReferenceTo: "
                        + modelClass.getName() + "." + load.source());
            }
            validateLoadField(modelClass, output, source, load);
            if (!outputFields.add(output.getName())) {
                throw new PlatformException("duplicate reference load output field: "
                        + modelClass.getName() + "." + output.getName());
            }
            if (load.hops().length == 0) {
                ReferencePlan plan = source.plan().withProjection(load.field(), output.getName());
                rules.put(load.source(), new ReferenceRule(source.field(), plan));
            }
        }
        return List.copyOf(rules.values());
    }

    private static List<DiscriminatedValuePlan> compileDiscriminatedValuePlans(Class<?> modelClass) {
        LinkedHashMap<String, Field> fields = fields(modelClass);
        List<DiscriminatedValuePlan> plans = new java.util.ArrayList<>();
        for (Field field : fields.values()) {
            DiscriminatedValue annotation = field.getAnnotation(DiscriminatedValue.class);
            if (annotation == null) continue;
            if (!fields.containsKey(annotation.discriminator())) {
                throw new PlatformException("discriminator field does not exist: " + modelClass.getName() + "." + annotation.discriminator());
            }
            if (!net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum.class.isAssignableFrom(annotation.enumType())) {
                throw new PlatformException("discriminator enum must implement CodeTitleEnum: " + annotation.enumType().getName());
            }
            Set<String> enumCodes = java.util.Arrays.stream(annotation.enumType().getEnumConstants())
                    .map(value -> ((net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum) value).getCode())
                    .collect(java.util.stream.Collectors.toSet());
            List<DiscriminatedValueCasePlan> cases = java.util.Arrays.stream(annotation.cases()).map(value -> {
                if (!enumCodes.contains(value.when())) {
                    throw new PlatformException("discriminator case is not declared by enum: " + value.when());
                }
                return discriminatedCasePlan(field.getName(), fields, value);
            }).toList();
            if (cases.size() != enumCodes.size() || !cases.stream().map(DiscriminatedValueCasePlan::when).collect(java.util.stream.Collectors.toSet()).containsAll(enumCodes)) {
                throw new PlatformException("discriminated value must declare every enum case: " + field.getName());
            }
            plans.add(new DiscriminatedValuePlan(field.getName(), annotation.discriminator(), enumCodes, cases));
        }
        return List.copyOf(plans);
    }

    private static DiscriminatedValueCasePlan discriminatedCasePlan(String valueField, Map<String, Field> fields,
                                                                      DiscriminatedValueCase value) {
        return switch (value.source()) {
            case FIXED -> new DiscriminatedValueCasePlan(value.when(), value.source(), value.fixedValue(), null, null);
            case FIELD -> {
                if (!fields.containsKey(value.sourceField())) {
                    throw new PlatformException("discriminator source field does not exist: " + value.sourceField());
                }
                yield new DiscriminatedValueCasePlan(value.when(), value.source(), null, value.sourceField(), null);
            }
            case REFERENCE -> {
                if (value.cardinality() != ReferenceCardinality.ONE) {
                    throw new PlatformException("discriminator reference must be ONE: " + valueField);
                }
                List<ReferenceCandidateDependency> dependencies = java.util.Arrays.stream(value.candidateBindings())
                        .map(binding -> {
                            if (!fields.containsKey(binding.sourceField())) {
                                throw new PlatformException("discriminator reference dependency source field does not exist: "
                                        + binding.sourceField());
                            }
                            return new ReferenceCandidateDependency(binding.sourceField(), binding.targetField(), binding.required());
                        })
                        .toList();
                yield new DiscriminatedValueCasePlan(value.when(), value.source(), null, null,
                        new ReferencePlan(valueField, targetOf(value), ReferenceCardinality.ONE, List.of(),
                                ReferenceIntegrityPolicy.from(value.integrity()), value.tenantScope(), dependencies, List.of()));
            }
        };
    }

    private static List<ReferenceLoadPath> compileLoadPaths(Class<?> modelClass) {
        LinkedHashMap<String, Field> fields = fields(modelClass);
        Map<String, ReferenceRule> rulesByField = rules(modelClass).stream()
                .collect(java.util.stream.Collectors.toMap(rule -> rule.plan().sourceField(), rule -> rule,
                        (first, ignored) -> first, LinkedHashMap::new));
        List<ReferenceLoadPath> paths = new java.util.ArrayList<>();
        for (Field output : fields.values()) {
            ReferenceLoad load = output.getAnnotation(ReferenceLoad.class);
            if (load == null || load.hops().length == 0) {
                continue;
            }
            ReferenceRule source = rulesByField.get(load.source());
            if (source == null) {
                throw new PlatformException("ReferenceLoad source must declare @ReferenceTo: "
                        + modelClass.getName() + "." + load.source());
            }
            validateLoadField(modelClass, output, source, load);
            if (source.cardinality() != ReferenceCardinality.ONE) {
                throw new PlatformException("multi-hop ReferenceLoad source must have cardinality ONE: "
                        + modelClass.getName() + "." + load.source());
            }
            List<ReferenceLoadPath.Hop> hops = java.util.Arrays.stream(load.hops())
                    .map(hop -> new ReferenceLoadPath.Hop(targetOfService(hop.target()), normalize(hop.via())))
                    .toList();
            paths.add(new ReferenceLoadPath(load.source(), source.target(), hops, load.field(), output.getName()));
        }
        return List.copyOf(paths);
    }

    private static List<ReferenceSummaryPlan> compileSummaryPlans(Class<?> modelClass) {
        LinkedHashMap<String, Field> fields = fields(modelClass);
        Map<String, ReferenceRule> rulesByField = rules(modelClass).stream()
                .collect(java.util.stream.Collectors.toMap(rule -> rule.plan().sourceField(), rule -> rule,
                        (first, ignored) -> first, LinkedHashMap::new));
        List<ReferenceSummaryPlan> summaries = new java.util.ArrayList<>();
        Set<String> outputFields = new LinkedHashSet<>();
        for (Field output : fields.values()) {
            ReferenceSummary summary = output.getAnnotation(ReferenceSummary.class);
            if (summary == null) {
                continue;
            }
            ReferenceRule source = rulesByField.get(summary.source());
            if (source == null) {
                throw new PlatformException("ReferenceSummary source must declare @ReferenceTo: "
                        + modelClass.getName() + "." + summary.source());
            }
            if (!Modifier.isTransient(output.getModifiers())) {
                throw new PlatformException("ReferenceSummary output must be transient: "
                        + modelClass.getName() + "." + output.getName());
            }
            boolean collection = Collection.class.isAssignableFrom(output.getType());
            if (source.cardinality() == ReferenceCardinality.MANY && !collection) {
                throw new PlatformException("ReferenceSummary output for MANY reference must be a Collection: "
                        + modelClass.getName() + "." + output.getName());
            }
            if (source.cardinality() == ReferenceCardinality.ONE && collection) {
                throw new PlatformException("ReferenceSummary output for ONE reference must not be a Collection: "
                        + modelClass.getName() + "." + output.getName());
            }
            List<String> summaryFields = java.util.Arrays.stream(summary.fields())
                    .map(StaticReferenceResolver::normalize)
                    .filter(java.util.Objects::nonNull)
                    .filter(field -> !"id".equals(field))
                    .distinct()
                    .toList();
            if (!outputFields.add(output.getName())) {
                throw new PlatformException("duplicate reference summary output field: "
                        + modelClass.getName() + "." + output.getName());
            }
            summaries.add(new ReferenceSummaryPlan(summary.source(), source.target(), source.cardinality(),
                    summaryFields, output.getName()));
        }
        return List.copyOf(summaries);
    }

    private static ReferencePlan referenceToPlan(Field field, ReferenceTo referenceTo) {
        return new ReferencePlan(
                field.getName(),
                targetOf(referenceTo),
                referenceTo.cardinality(),
                List.of(),
                ReferenceIntegrityPolicy.from(referenceTo.integrity()),
                referenceTo.tenantScope(),
                java.util.Arrays.stream(referenceTo.candidateBindings())
                        .map(binding -> new ReferenceCandidateDependency(binding.sourceField(), binding.targetField(),
                                binding.required()))
                        .toList(),
                java.util.Arrays.stream(referenceTo.selectionProjections())
                        .map(ReferenceSelectionProjection::new).toList()
        );
    }

    private static ReferenceTarget targetOf(ReferenceTo reference) {
        boolean hasTargetClass = reference.target() != null && reference.target() != Void.class;
        boolean hasTargetAlias = reference.moduleAlias() != null && !reference.moduleAlias().isBlank();
        boolean hasEntityAlias = reference.entityAlias() != null && !reference.entityAlias().isBlank();
        if (hasTargetClass == hasTargetAlias || hasTargetAlias != hasEntityAlias) {
            throw new PlatformException("ReferenceTo requires exactly one of target or moduleAlias/entityAlias");
        }
        if (hasTargetAlias) {
            return ReferenceTarget.of(reference.moduleAlias(), reference.entityAlias());
        }
        try {
            return targetOfService(reference.target());
        } catch (PlatformException ex) {
            throw ex;
        }
    }

    private static ReferenceTarget targetOf(DiscriminatedValueCase reference) {
        boolean hasTargetClass = reference.target() != null && reference.target() != Void.class;
        boolean hasTargetAlias = reference.moduleAlias() != null && !reference.moduleAlias().isBlank();
        boolean hasEntityAlias = reference.entityAlias() != null && !reference.entityAlias().isBlank();
        if (hasTargetClass == hasTargetAlias || hasTargetAlias != hasEntityAlias) {
            throw new PlatformException("discriminator reference requires exactly one of target or moduleAlias/entityAlias");
        }
        return hasTargetAlias ? ReferenceTarget.of(reference.moduleAlias(), reference.entityAlias()) : targetOfService(reference.target());
    }

    public static ReferenceTarget targetOfService(Class<?> serviceType) {
        if (serviceType == null || serviceType == Void.class) {
            throw new PlatformException("ReferenceLoad target must declare a service class");
        }
        try {
            Field moduleAliasField = serviceType.getField("MODULE_ALIAS");
            if (!Modifier.isStatic(moduleAliasField.getModifiers()) || moduleAliasField.getType() != String.class) {
                throw new PlatformException("ReferenceTo target MODULE_ALIAS must be public static String: "
                        + serviceType.getName());
            }
            String moduleAlias = (String) moduleAliasField.get(null);
            return ReferenceTargets.fromModuleAlias(moduleAlias);
        } catch (ReflectiveOperationException ex) {
            throw new PlatformException("ReferenceTo target requires public MODULE_ALIAS: " + serviceType.getName(), ex);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LinkedHashMap<String, Field> fields(Class<?> modelClass) {
        LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
        Class<?> current = modelClass;
        while (current != null && !Object.class.equals(current)) {
            for (Field field : current.getDeclaredFields()) {
                fields.putIfAbsent(field.getName(), field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static void validateLoadField(Class<?> modelClass,
                                          Field output,
                                          ReferenceRule source,
                                          ReferenceLoad load) {
        if (load.field() == null || load.field().isBlank()) {
            throw new PlatformException("ReferenceLoad field must not be blank: "
                    + modelClass.getName() + "." + output.getName());
        }
        if (!Modifier.isTransient(output.getModifiers())) {
            throw new PlatformException("ReferenceLoad output must be transient: "
                    + modelClass.getName() + "." + output.getName());
        }
        boolean collection = Collection.class.isAssignableFrom(output.getType());
        if (source.cardinality() == ReferenceCardinality.MANY && !collection) {
            throw new PlatformException("ReferenceLoad output for MANY reference must be a Collection: "
                    + modelClass.getName() + "." + output.getName());
        }
        if (source.cardinality() == ReferenceCardinality.ONE && collection) {
            throw new PlatformException("ReferenceLoad output for ONE reference must not be a Collection: "
                    + modelClass.getName() + "." + output.getName());
        }
    }

    public record ReferenceRule(Field field, ReferencePlan plan) {
        public ReferenceTarget target() {
            return plan.target();
        }

        public ReferenceCardinality cardinality() {
            return plan.cardinality();
        }

        public ReferenceIntegrityPolicy integrity() {
            return plan.integrity();
        }

        private List<String> values(Object record) {
            try {
                return plan.normalizeValues(field.get(record));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read reference field: " + field.getName(), e);
            }
        }
    }

    private static Object readByFieldName(Object record, String fieldName) {
        Field field = readField(record.getClass(), fieldName);
        try {
            field.setAccessible(true);
            return field.get(record);
        } catch (IllegalAccessException e) {
            throw new PlatformException("Cannot read reference field: " + fieldName, e);
        }
    }

    private static Field readField(Class<?> modelClass, String fieldName) {
        Class<?> current = modelClass;
        while (current != null && !Object.class.equals(current)) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new PlatformException("Cannot find reference field: " + modelClass.getName() + "." + fieldName);
    }

    private static void writeByFieldName(Object record, String fieldName, Object value) {
        Class<?> current = record.getClass();
        while (current != null && !Object.class.equals(current)) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(record, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new PlatformException("Cannot write reference title field: " + fieldName, e);
            } catch (IllegalArgumentException e) {
                throw new PlatformException("Cannot write reference title field: "
                        + record.getClass().getName() + "." + fieldName
                        + ", value type: " + valueType(value), e);
            }
        }
        throw new PlatformException("Cannot find reference title field: " + record.getClass().getName() + "." + fieldName);
    }

    private static String valueType(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
