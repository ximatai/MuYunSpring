package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface ReferencerAbility<T extends EntityContract> extends CrudAbility<T> {
    default Map<ReferenceTarget, Set<String>> collectReferenceIdsByTarget(T entity) {
        Class<?> modelClass = referenceModelClass(entity);
        return modelClass == null
                ? Map.of()
                : StaticReferenceResolver.collect(modelClass, entity);
    }

    default void afterReferenceSelect(T entity) {
        populateStaticReferenceTitles(entity);
    }

    /**
     * Validates non-empty static references against the target service's active
     * scope. This is a write-side integrity check; title projection remains a
     * read-side concern.
     */
    default void validateReferenceIntegrity(T entity) {
        validateReferenceIntegrity(null, entity);
    }

    /**
     * Validates references for a write. During a normal update, a
     * {@link ReferenceTargetUnavailablePolicy#PRESERVE_HISTORY} reference may
     * retain an unavailable value that was already persisted on the record.
     */
    default void validateReferenceIntegrity(T existing, T entity) {
        Class<?> modelClass = referenceModelClass(entity);
        if (entity == null || modelClass == null) {
            return;
        }
        for (StaticReferenceResolver.ReferenceRule rule : StaticReferenceResolver.rules(modelClass)) {
            List<String> ids = StaticReferenceResolver.values(entity, rule.plan());
            if (ids.isEmpty()) {
                continue;
            }
            Map<String, String> resolved = referenceTitles(rule.target(), ids);
            List<String> persistedIds = existing == null
                    ? List.of()
                    : StaticReferenceResolver.values(existing, rule.plan());
            List<String> unavailable = ids.stream()
                    .filter(id -> !resolved.containsKey(id))
                    .filter(id -> rule.integrity().onTargetUnavailable()
                            != ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY
                            || !persistedIds.contains(id))
                    .toList();
            if (!unavailable.isEmpty()) {
                throw new PlatformException("reference target is unavailable: "
                        + rule.target().qualifiedName() + "." + rule.plan().sourceField()
                        + " -> " + unavailable);
            }
        }
    }

    default void refreshReferenceDependencies(T entity) {
        ReferenceDependencyRegistry.refresh(this, entity);
    }

    default void clearReferenceDependency(String id) {
        if (this instanceof CacheAbility<?> cacheAbility) {
            ReferenceDependencyRegistry.removeReferrer(cacheAbility.cacheNamespace(), id);
        }
    }

    default void populateStaticReferenceTitles(T entity) {
        Class<?> modelClass = referenceModelClass(entity);
        if (modelClass == null) {
            return;
        }
        for (ReferencePlan plan : StaticReferenceResolver.plans(modelClass)) {
            if (plan.projections().isEmpty()) {
                continue;
            }
            List<String> ids = referenceSourceValues(entity, plan);
            if (ids.isEmpty()) {
                clearProjectionValues(entity, plan);
                continue;
            }
            populateProjectionValues(entity, plan, ids);
        }
    }

    default List<String> referenceSourceValues(T entity, ReferencePlan plan) {
        return StaticReferenceResolver.values(entity, plan);
    }

    default Map<String, String> referenceTitles(ReferenceTarget target, Collection<String> ids) {
        return requireReferenceAbility(target, "title").titles(ids);
    }

    default Map<String, Map<String, Object>> referenceProjections(ReferenceTarget target,
                                                                  Collection<String> ids,
                                                                  Collection<String> sourceFields) {
        return requireReferenceAbility(target, "projection").projections(ids, sourceFields);
    }

    private ReferenceAbility<?> requireReferenceAbility(ReferenceTarget target, String purpose) {
        if (target == null) {
            throw new PlatformException("reference " + purpose + " resolver target must not be null");
        }
        ReferenceAbility<?> resolved = net.ximatai.muyun.spring.ability.PlatformAbilityRuntime
                .referenceTargetResolver()
                .resolve(target)
                .orElse(null);
        if (resolved != null) {
            return resolved;
        }
        throw new PlatformException("reference " + purpose + " resolver is not configured: "
                + target.qualifiedName());
    }

    private Class<?> referenceModelClass(T entity) {
        Class<?> modelClass = modelClass();
        if (modelClass != null) {
            return modelClass;
        }
        return entity == null ? null : entity.getClass();
    }

    private void populateProjectionValues(T entity, ReferencePlan plan, List<String> ids) {
        if (plan.projections().isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> loaded = referenceProjections(plan.target(), ids, projectionSourceFields(plan));
        for (ReferenceProjection projection : plan.projections()) {
            StaticReferenceResolver.writeLoadedValue(entity, projection.outputField(),
                    referenceProjectionValue(ids, loaded, plan, projection.targetField()));
        }
    }

    private void clearProjectionValues(T entity, ReferencePlan plan) {
        for (ReferenceProjection projection : plan.projections()) {
            StaticReferenceResolver.writeLoadedValue(entity, projection.outputField(), null);
        }
    }

    private List<String> projectionSourceFields(ReferencePlan plan) {
        return plan.projections().stream()
                .map(ReferenceProjection::targetField)
                .distinct()
                .toList();
    }

    private Object referenceProjectionValue(List<String> ids,
                                            Map<String, Map<String, Object>> loaded,
                                            ReferencePlan plan,
                                            String sourceField) {
        if (loaded == null) {
            loaded = Map.of();
        }
        Map<String, Map<String, Object>> loadedValues = loaded;
        if (plan.cardinality() == ReferenceCardinality.MANY) {
            return ids.stream()
                    .map(id -> fieldValue(loadedValues, id, sourceField))
                    .filter(Objects::nonNull)
                    .toList();
        }
        return fieldValue(loadedValues, ids.getFirst(), sourceField);
    }

    private Object fieldValue(Map<String, Map<String, Object>> loaded, String id, String sourceField) {
        Map<String, Object> fields = loaded.get(id);
        return fields == null ? null : fields.get(sourceField);
    }
}
