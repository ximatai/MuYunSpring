package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionRecoveryAbility;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves recovery roots and their explicitly owned, cascade-deleted soft children. */
@Component
public class StaticDeletionRecoveryResourceResolver implements
        DeletionRecoveryResourceResolver,
        SmartInitializingSingleton {
    private final ListableBeanFactory beanFactory;
    private volatile Map<DeletionResourceIdentity, SoftDeleteAbility<?>> abilities;

    @Autowired
    public StaticDeletionRecoveryResourceResolver(ListableBeanFactory beanFactory) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory must not be null");
        this.abilities = Map.of();
    }

    public StaticDeletionRecoveryResourceResolver(Collection<DeletionRecoveryAbility<?>> abilities) {
        this.beanFactory = null;
        this.abilities = indexAbilities(abilities);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (beanFactory != null) {
            abilities = indexAbilities(recoveryAbilities(beanFactory));
        }
    }

    private static Map<DeletionResourceIdentity, SoftDeleteAbility<?>> indexAbilities(
            Collection<DeletionRecoveryAbility<?>> abilities) {
        Map<DeletionResourceIdentity, SoftDeleteAbility<?>> indexed = new LinkedHashMap<>();
        for (DeletionRecoveryAbility<?> ability
                : abilities == null ? List.<DeletionRecoveryAbility<?>>of() : abilities) {
            indexOwnedTree(ability, indexed);
        }
        return Map.copyOf(indexed);
    }

    private static void indexOwnedTree(SoftDeleteAbility<?> ability,
                                       Map<DeletionResourceIdentity, SoftDeleteAbility<?>> indexed) {
        if (ability == null) return;
        DeletionResourceIdentity key = DeletionResourceIdentity.of(ability);
        SoftDeleteAbility<?> previous = indexed.putIfAbsent(key, ability);
        if (previous != null) {
            if (!previous.mutationServiceIdentity().equals(ability.mutationServiceIdentity())) {
                throw new IllegalArgumentException("Duplicate static deletion recovery ability for " + key + ": "
                        + previous.getClass().getName() + ", " + ability.getClass().getName());
            }
            return;
        }
        if (ability instanceof ChildrenAbility<?> parent) {
            for (var relation : parent.childRelations()) {
                if (relation.isCascadeOnParentUnavailable()
                        && relation.childAbility() instanceof SoftDeleteAbility<?> child) {
                    indexOwnedTree(child, indexed);
                }
            }
        }
    }

    @Override
    public boolean supports(DeletionEntry entry) {
        Objects.requireNonNull(entry, "deletionEntry must not be null");
        return abilities.containsKey(DeletionResourceIdentity.from(entry));
    }

    @Override
    public Optional<SoftDeleteAbility<?>> resolve(DeletionEntry entry) {
        Objects.requireNonNull(entry, "deletionEntry must not be null");
        return Optional.ofNullable(abilities.get(DeletionResourceIdentity.from(entry)));
    }

    @Override
    public boolean canPurgeAggregateChild(DeletionEntry entry, DeletionEntry parent) {
        if (parent == null || entry.getTriggerType() != DeletionEntryTrigger.CASCADE
                || !Objects.equals(entry.getParentEntryId(), parent.getId())
                || !Objects.equals(entry.getOperationId(), parent.getOperationId())
                || !Objects.equals(entry.getTenantId(), parent.getTenantId())) return false;
        SoftDeleteAbility<?> ownerAbility = abilities.get(DeletionResourceIdentity.from(parent));
        SoftDeleteAbility<?> childAbility = abilities.get(DeletionResourceIdentity.from(entry));
        if (!(ownerAbility instanceof ChildrenAbility<?> aggregate) || childAbility == null) return false;
        // A resource with its own recycle bin must use that policy, including an explicit purge denial.
        if (childAbility instanceof RecycleBinAbility<?>) return false;
        var relations = aggregate.childRelations().stream()
                .filter(relation -> relation.isCascadeOnParentUnavailable()
                        && relation.childAbility().mutationServiceIdentity().equals(childAbility.mutationServiceIdentity()))
                .toList();
        if (relations.isEmpty()) return false;
        var owner = ownerAbility.selectIgnoreSoftDelete(parent.getResourceRecordId());
        var child = childAbility.selectIgnoreSoftDelete(entry.getResourceRecordId());
        if (owner == null || child == null || !Boolean.TRUE.equals(owner.getDeleted())
                || !Boolean.TRUE.equals(child.getDeleted())
                || parent.getResourceVersion() == null || entry.getResourceVersion() == null
                || !Objects.equals(owner.getVersion(), parent.getResourceVersion())
                || !Objects.equals(child.getVersion(), entry.getResourceVersion())
                || !Objects.equals(owner.getTenantId(), parent.getTenantId())
                || !Objects.equals(child.getTenantId(), entry.getTenantId())
                || relations.stream().filter(relation -> relation.ownsRetainedChild(owner.getId(), child)).count() != 1) {
            throw new OptimisticLockException("aggregate ownership or retained version changed after source deletion");
        }
        return true;
    }

    @SuppressWarnings("rawtypes")
    private static Collection<DeletionRecoveryAbility<?>> recoveryAbilities(ListableBeanFactory beanFactory) {
        List<DeletionRecoveryAbility<?>> abilities = new ArrayList<>();
        beanFactory.getBeansOfType(DeletionRecoveryAbility.class).values().forEach(abilities::add);
        return abilities;
    }
}
