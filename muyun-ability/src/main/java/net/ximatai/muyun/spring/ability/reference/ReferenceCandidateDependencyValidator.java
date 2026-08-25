package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Write-side validation for declarative reference candidate dependencies. */
public final class ReferenceCandidateDependencyValidator {
    private ReferenceCandidateDependencyValidator() {
    }

    public static void validate(Object entity,
                                Collection<String> ids,
                                ReferencePlan plan,
                                ReferenceAbility<?> target) {
        List<ReferenceCandidateDependency> dependencies = plan.candidateDependencies();
        if (entity == null || ids == null || ids.isEmpty() || dependencies.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> targets = target.projections(ids,
                dependencies.stream().map(ReferenceCandidateDependency::targetField).toList());
        for (String id : ids) {
            Map<String, Object> targetValues = targets.get(id);
            for (ReferenceCandidateDependency dependency : dependencies) {
                Object sourceValue = StaticReferenceResolver.readLoadedValue(entity, dependency.sourceField());
                if (dependency.required() && (sourceValue == null || String.valueOf(sourceValue).isBlank())) {
                    throw new PlatformException("reference dependency is required: " + dependency.sourceField());
                }
                Object targetValue = targetValues == null ? null : targetValues.get(dependency.targetField());
                if (sourceValue != null && !Objects.equals(String.valueOf(sourceValue), String.valueOf(targetValue))) {
                    throw new PlatformException("reference target does not satisfy dependency: "
                            + dependency.sourceField());
                }
            }
        }
    }
}
