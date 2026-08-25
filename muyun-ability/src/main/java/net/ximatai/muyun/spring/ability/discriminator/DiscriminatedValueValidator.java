package net.ximatai.muyun.spring.ability.discriminator;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateDependencyValidator;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.List;
import java.util.Map;

/** Normalizes and validates static discriminated fields through the standard reference facade. */
public final class DiscriminatedValueValidator {
    private DiscriminatedValueValidator() {
    }

    public static void normalizeAndValidate(Object record) {
        normalizeAndValidate(record == null ? null : record.getClass(), record);
    }

    /** Uses the service-declared model class when a static service stores a proxy or subtype. */
    public static void normalizeAndValidate(Class<?> modelClass, Object record) {
        if (record == null) return;
        Class<?> resolvedModelClass = modelClass == null ? record.getClass() : modelClass;
        for (DiscriminatedValuePlan plan : StaticReferenceResolver.discriminatedValuePlans(resolvedModelClass)) {
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
        ReferenceCandidateDependencyValidator.validate(record, values, reference, ability);
    }
}
