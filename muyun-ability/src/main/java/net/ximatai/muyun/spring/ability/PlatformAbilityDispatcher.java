package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbilityResolver;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleListener;
import net.ximatai.muyun.spring.ability.deletion.DeletionMode;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;
import net.ximatai.muyun.spring.ability.deletion.DeletionTransactionOperator;
import net.ximatai.muyun.spring.ability.option.StaticOptionFieldValueValidator;
import net.ximatai.muyun.spring.ability.reference.ReferencerAbility;
import net.ximatai.muyun.spring.ability.reference.ReferencedByResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadObserver;
import net.ximatai.muyun.spring.ability.reference.ReferenceDeletionGuard;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueValidator;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.List;
import java.util.Map;

final class PlatformAbilityDispatcher {
    private static volatile StaticOptionFieldValueValidator staticOptionFieldValueValidator =
            StaticOptionFieldValueValidator.NONE;
    private static volatile DeletionLifecycleListener deletionLifecycleListener = DeletionLifecycleListener.NONE;
    private static volatile DeletionTransactionOperator deletionTransactionOperator = DeletionTransactionOperator.NONE;
    private static volatile ReferenceDeletionGuard referenceDeletionGuard = ReferenceDeletionGuard.NONE;
    private static volatile ReferenceTargetResolver referenceTargetResolver = ReferenceTargetResolver.NONE;
    private static volatile ReferencedByResolver referencedByResolver = ReferencedByResolver.NONE;
    private static volatile ReferenceLoadResolver referenceLoadResolver = ReferenceLoadResolver.NONE;
    private static volatile ReferenceReadObserver referenceReadObserver = ReferenceReadObserver.NONE;
    private static volatile ChildAbilityResolver childAbilityResolver = ChildAbilityResolver.NONE;
    private static volatile EntitySaveLifecycleListener entitySaveLifecycleListener = EntitySaveLifecycleListener.NONE;

    private PlatformAbilityDispatcher() {
    }

    static void setStaticOptionFieldValueValidator(StaticOptionFieldValueValidator validator) {
        staticOptionFieldValueValidator = validator == null ? StaticOptionFieldValueValidator.NONE : validator;
    }

    static void resetStaticOptionFieldValueValidator() {
        staticOptionFieldValueValidator = StaticOptionFieldValueValidator.NONE;
    }

    static void setDeletionLifecycleListener(DeletionLifecycleListener listener) {
        deletionLifecycleListener = listener == null ? DeletionLifecycleListener.NONE : listener;
    }

    static void resetDeletionLifecycleListener() {
        deletionLifecycleListener = DeletionLifecycleListener.NONE;
    }

    static void setDeletionTransactionOperator(DeletionTransactionOperator operator) {
        deletionTransactionOperator = operator == null ? DeletionTransactionOperator.NONE : operator;
    }

    static void resetDeletionTransactionOperator() {
        deletionTransactionOperator = DeletionTransactionOperator.NONE;
    }

    static <T> T inDeletionTransaction(java.util.function.Supplier<T> work) {
        return deletionTransactionOperator.execute(work);
    }

    static void setReferenceDeletionGuard(ReferenceDeletionGuard guard) {
        referenceDeletionGuard = guard == null ? ReferenceDeletionGuard.NONE : guard;
    }

    static void resetReferenceDeletionGuard() {
        referenceDeletionGuard = ReferenceDeletionGuard.NONE;
    }

    static void setReferenceTargetResolver(ReferenceTargetResolver resolver) {
        referenceTargetResolver = resolver == null ? ReferenceTargetResolver.NONE : resolver;
    }

    static void resetReferenceTargetResolver() {
        referenceTargetResolver = ReferenceTargetResolver.NONE;
    }

    static ReferenceTargetResolver referenceTargetResolver() {
        return referenceTargetResolver;
    }

    static void setChildAbilityResolver(ChildAbilityResolver resolver) {
        childAbilityResolver = resolver == null ? ChildAbilityResolver.NONE : resolver;
    }

    static void resetChildAbilityResolver() {
        childAbilityResolver = ChildAbilityResolver.NONE;
    }

    static void setEntitySaveLifecycleListener(EntitySaveLifecycleListener listener) {
        entitySaveLifecycleListener = listener == null ? EntitySaveLifecycleListener.NONE : listener;
    }

    static void resetEntitySaveLifecycleListener() {
        entitySaveLifecycleListener = EntitySaveLifecycleListener.NONE;
    }

    static ChildAbilityResolver childAbilityResolver() {
        return childAbilityResolver;
    }

    static void setReferencedByResolver(ReferencedByResolver resolver) {
        referencedByResolver = resolver == null ? ReferencedByResolver.NONE : resolver;
    }

    static void resetReferencedByResolver() {
        referencedByResolver = ReferencedByResolver.NONE;
    }

    static void setReferenceLoadResolver(ReferenceLoadResolver resolver) {
        referenceLoadResolver = resolver == null ? ReferenceLoadResolver.NONE : resolver;
    }

    static void resetReferenceLoadResolver() {
        referenceLoadResolver = ReferenceLoadResolver.NONE;
    }

    static void setReferenceReadObserver(ReferenceReadObserver observer) {
        referenceReadObserver = observer == null ? ReferenceReadObserver.NONE : observer;
    }

    static void resetReferenceReadObserver() {
        referenceReadObserver = ReferenceReadObserver.NONE;
    }

    static ReferenceReadObserver referenceReadObserver() {
        return referenceReadObserver;
    }

    static <T extends EntityContract> void beforeTargetUnavailable(CrudAbility<T> ability,
                                                                    T entity,
                                                                    DeletionContext context,
                                                                    DeletionNode node,
                                                                    DeletionMode mode) {
        referenceDeletionGuard.validateTargetUnavailable(ability, entity);
        runChildrenBeforeDelete(ability, entity, context, node);
        referenceDeletionGuard.cascadeTargetUnavailable(ability, entity, context, node, mode);
    }

    static <T extends EntityContract> void beforeRestore(CrudAbility<T> ability, T entity) {
        runReferenceIntegrityValidation(ability, null, entity, false);
    }

    static DeletionContext rootDeletionContext(String moduleAlias, String recordId) {
        net.ximatai.muyun.spring.ability.deletion.DeletionResource root =
                new net.ximatai.muyun.spring.ability.deletion.DeletionResource(moduleAlias, recordId);
        return DeletionContext.root(moduleAlias, recordId, deletionLifecycleListener.open(root));
    }

    static DeletionContext resolveDeletionContext(String moduleAlias,
                                                  String recordId,
                                                  DeletionContext requestedContext) {
        if (requestedContext == null) {
            return rootDeletionContext(moduleAlias, recordId);
        }
        if (requestedContext.trigger() == net.ximatai.muyun.spring.ability.deletion.DeletionTrigger.DIRECT
                && !requestedContext.hasLifecycleSession()) {
            return rootDeletionContext(moduleAlias, recordId);
        }
        return requestedContext;
    }

    static <T extends EntityContract> DeletionNode deletionStarted(CrudAbility<T> ability,
                                                                    T entity,
                                                                    DeletionContext context,
                                                                    DeletionMode mode) {
        return context.lifecycleSession().started(ability, entity, context, mode);
    }

    static <T extends EntityContract> void deletionSucceeded(CrudAbility<T> ability,
                                                              T entity,
                                                              DeletionContext context,
                                                              DeletionNode node,
                                                              DeletionMode mode) {
        context.lifecycleSession().succeeded(ability, entity, context, node, mode);
    }

    static <T extends EntityContract> void deletionFailed(CrudAbility<T> ability,
                                                           T entity,
                                                           DeletionContext context,
                                                           DeletionNode node,
                                                           DeletionMode mode,
                                                           RuntimeException failure) {
        context.lifecycleSession().failed(ability, entity, context, node, mode, failure);
    }

    static <T extends EntityContract> void beforeInsertSave(CrudAbility<T> ability, T entity) {
        beforeSave(ability, null, entity, false);
    }

    static <T extends EntityContract> void beforeUpdateSave(CrudAbility<T> ability, T existing, T entity) {
        beforeSave(ability, existing, entity, true);
    }

    private static <T extends EntityContract> void beforeSave(CrudAbility<T> ability,
                                                                T existing,
                                                                T entity,
                                                                boolean update) {
        // Discriminated fields may derive a persisted value from the selected branch. Normalize
        // them before generic option/reference checks so every later write validator sees one
        // coherent record, regardless of whether the declaration is static or dynamic.
        Class<?> modelClass = ability.modelClass() == null ? entity.getClass() : ability.modelClass();
        DiscriminatedValueValidator.normalizeAndValidate(modelClass, entity);
        runStaticOptionFieldValidation(ability, entity);
        runReferenceIntegrityValidation(ability, existing, entity, update);
        TenantUniqueConstraintSupport.validate(ability, entity);
        entitySaveLifecycleListener.beforeSave(ability, existing, entity);
    }

    static <T extends EntityContract> void persisted(CrudAbility<T> ability, T entity) {
        entitySaveLifecycleListener.persisted(ability, entity);
    }

    static <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T entity, RuntimeException failure) {
        entitySaveLifecycleListener.persistFailed(ability, entity, failure);
    }

    static <T extends EntityContract> void afterInsert(CrudAbility<T> ability, String id, T entity) {
        runChildrenAfterInsert(ability, id, entity);
        ability.afterPlatformInsert(id, entity);
    }

    static <T extends EntityContract> void afterUpdate(CrudAbility<T> ability, T entity, int updated) {
        runChildrenAfterUpdate(ability, entity, updated);
        ability.afterPlatformUpdate(entity, updated);
    }

    static <T extends EntityContract> void afterDelete(CrudAbility<T> ability,
                                                        String id,
                                                        T entity,
                                                        int deleted,
                                                        DeletionContext context,
                                                        DeletionNode node) {
        ability.afterPlatformDelete(id, entity, deleted);
    }

    static <T extends EntityContract> void afterSelect(CrudAbility<T> ability, T entity) {
        runFieldProtectionAfterSelect(ability, entity);
        runChildrenAfterSelect(ability, entity);
        runReferenceAfterSelect(ability, entity);
        runReferenceLoadAfterSelect(ability, entity);
        runReferencedByAfterSelect(ability, entity);
        ability.afterPlatformSelect(entity);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static <T extends EntityContract> FieldProtectionAbility.FieldProtectionMutation beforePersist(CrudAbility<T> ability,
                                                                                                   T entity) {
        if (ability instanceof FieldProtectionAbility fieldProtectionAbility) {
            return fieldProtectionAbility.protectFieldsForStorage(entity);
        }
        return FieldProtectionAbility.FieldProtectionMutation.NONE;
    }

    private static <T extends EntityContract> void runStaticOptionFieldValidation(CrudAbility<T> ability, T entity) {
        if (ability == null || entity == null) {
            return;
        }
        Class<?> modelClass = ability.modelClass() == null ? entity.getClass() : ability.modelClass();
        staticOptionFieldValueValidator.validate(modelClass, entity);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends EntityContract> void runChildrenAfterInsert(CrudAbility<T> ability, String id, T entity) {
        if (ability instanceof ChildrenAbility childrenAbility) {
            childrenAbility.afterChildrenInsert(id, entity);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends EntityContract> void runChildrenAfterUpdate(CrudAbility<T> ability, T entity, int updated) {
        if (ability instanceof ChildrenAbility childrenAbility) {
            childrenAbility.afterChildrenUpdate(entity, updated);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends EntityContract> void runChildrenBeforeDelete(CrudAbility<T> ability,
                                                                            T entity,
                                                                            DeletionContext context,
                                                                            DeletionNode node) {
        if (ability instanceof ChildrenAbility childrenAbility) {
            childrenAbility.beforeChildrenDelete(entity.getId(), entity, context, node);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends EntityContract> void runChildrenAfterSelect(CrudAbility<T> ability, T entity) {
        if (ability instanceof ChildrenAbility childrenAbility) {
            childrenAbility.afterChildrenSelect(entity);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends EntityContract> void runReferenceAfterSelect(CrudAbility<T> ability, T entity) {
        if (ability instanceof ReferencerAbility referencerAbility) {
            referencerAbility.afterReferenceSelect(entity);
            referencerAbility.refreshReferenceDependencies(entity);
        }
    }

    private static <T extends EntityContract> void runReferencedByAfterSelect(CrudAbility<T> ability, T entity) {
        referencedByResolver.populate(ability, entity);
    }

    private static <T extends EntityContract> void runReferenceLoadAfterSelect(CrudAbility<T> ability, T entity) {
        referenceLoadResolver.populate(ability, entity);
    }

    static void populateReferenceLoads(CrudAbility<?> ability, java.util.Collection<? extends EntityContract> entities) {
        referenceLoadResolver.populateAll(ability, entities);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends EntityContract> void runReferenceIntegrityValidation(CrudAbility<T> ability,
                                                                                   T existing,
                                                                                   T entity,
                                                                                   boolean update) {
        if (entity == null) {
            return;
        }
        Class<?> modelClass = ability.modelClass() == null ? entity.getClass() : ability.modelClass();
        if (StaticReferenceResolver.rules(modelClass).isEmpty()) {
            return;
        }
        if (!(ability instanceof ReferencerAbility) && referenceTargetResolver == ReferenceTargetResolver.NONE) {
            return;
        }
        T persisted = existing == null && update && entity != null
                ? ability.selectActiveRaw(entity.getId())
                : existing;
        if (ability instanceof ReferencerAbility referencerAbility) {
            referencerAbility.validateReferenceIntegrity(persisted, entity);
            return;
        }
        for (StaticReferenceResolver.ReferenceRule rule : StaticReferenceResolver.rules(modelClass)) {
            List<String> ids = StaticReferenceResolver.values(entity, rule.plan());
            if (ids.isEmpty()) {
                continue;
            }
            ReferenceAbility<?> target = referenceTargetResolver.resolve(rule.target())
                    .orElseThrow(() -> new PlatformException("reference target is not registered: "
                            + rule.target().qualifiedName()));
            Map<String, String> resolved = target.titles(ids);
            List<String> preservedIds = persisted == null
                    ? List.of()
                    : StaticReferenceResolver.values(persisted, rule.plan());
            List<String> unavailable = ids.stream()
                    .filter(id -> !resolved.containsKey(id))
                    .filter(id -> rule.integrity().onTargetUnavailable()
                            != net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY
                            || !preservedIds.contains(id))
                    .toList();
            if (!unavailable.isEmpty()) {
                throw new PlatformException("reference target is unavailable: "
                        + rule.target().qualifiedName() + "." + rule.plan().sourceField()
                        + " -> " + unavailable);
            }
            net.ximatai.muyun.spring.ability.reference.ReferenceCandidateDependencyValidator.validate(
                    entity, ids, rule.plan(), target);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends EntityContract> void runFieldProtectionAfterSelect(CrudAbility<T> ability, T entity) {
        if (ability instanceof FieldProtectionAbility fieldProtectionAbility) {
            fieldProtectionAbility.restoreProtectedFieldsFromStorage(entity);
        }
    }
}
