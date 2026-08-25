package net.ximatai.muyun.spring.ability.discriminator;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateDependency;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Normalizes and validates static discriminated fields through the standard reference facade. */
public final class DiscriminatedValueValidator {
    private DiscriminatedValueValidator() {
    }

    public static void normalizeAndValidate(Object record) {
        if (record == null) return;
        for (DiscriminatedValuePlan plan : StaticReferenceResolver.discriminatedValuePlans(record.getClass())) {
            normalizeAndValidate(record, plan);
        }
    }

    private static void normalizeAndValidate(Object record, DiscriminatedValuePlan plan) {
        Object discriminator = StaticReferenceResolver.readLoadedValue(record, plan.discriminatorField());
        DiscriminatedValueCasePlan branch = plan.caseFor(discriminator);
        if (branch == null) {
            throw new PlatformException("discriminator value has no declared branch: " + plan.valueField());
        }
        switch (branch.source()) {
            case FIXED -> StaticReferenceResolver.writeLoadedValue(record, plan.valueField(), branch.fixedValue());
            case FIELD -> {
                Object value = StaticReferenceResolver.readLoadedValue(record, branch.sourceField());
                if (value == null || String.valueOf(value).isBlank()) {
                    throw new PlatformException("discriminator source field is required: " + branch.sourceField());
                }
                StaticReferenceResolver.writeLoadedValue(record, plan.valueField(), value);
            }
            case REFERENCE -> validateReference(record, plan.valueField(), branch.reference());
        }
    }

    private static void validateReference(Object record, String valueField, ReferencePlan reference) {
        List<String> values = reference.normalizeValues(StaticReferenceResolver.readLoadedValue(record, valueField));
        if (values.isEmpty()) throw new PlatformException("discriminator reference value is required: " + valueField);
        ReferenceTarget target = reference.target();
        var ability = PlatformAbilityRuntime.referenceTargetResolver().resolve(target).orElse(null);
        // A model-only unit test may deliberately run without the optional platform target catalog.
        // In an assembled runtime the catalog is present and this branch is always validated through it.
        if (ability == null) return;
        Map<String, String> titles = ability.titles(values);
        if (titles.size() != values.size()) {
            throw new PlatformException("discriminator reference target is unavailable: " + target.qualifiedName() + "." + valueField);
        }
        List<ReferenceCandidateDependency> dependencies = reference.candidateDependencies();
        if (dependencies.isEmpty()) return;
        Map<String, Map<String, Object>> targets = ability.projections(values,
                dependencies.stream().map(ReferenceCandidateDependency::targetField).toList());
        for (String id : values) {
            Map<String, Object> targetValues = targets.get(id);
            for (ReferenceCandidateDependency dependency : dependencies) {
                Object source = StaticReferenceResolver.readLoadedValue(record, dependency.sourceField());
                if (dependency.required() && (source == null || String.valueOf(source).isBlank())) {
                    throw new PlatformException("discriminator reference dependency is required: " + dependency.sourceField());
                }
                if (source != null && !Objects.equals(String.valueOf(source), String.valueOf(targetValues == null ? null : targetValues.get(dependency.targetField())))) {
                    throw new PlatformException("discriminator reference target does not satisfy dependency: " + dependency.sourceField());
                }
            }
        }
    }
}
