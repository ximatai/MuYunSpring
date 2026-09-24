package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/** Resolves dynamic resources through the current dynamic runtime, never as singleton Ability beans. */
@Component
public class DynamicDeletionRecoveryResourceResolver implements DeletionRecoveryResourceResolver {
    private final Optional<DynamicRecordRuntime> dynamicRecords;

    public DynamicDeletionRecoveryResourceResolver(Optional<DynamicRecordRuntime> dynamicRecords) {
        this.dynamicRecords = dynamicRecords == null ? Optional.empty() : dynamicRecords;
    }

    @Override
    public boolean supports(DeletionEntry entry) {
        Objects.requireNonNull(entry, "deletionEntry must not be null");
        if (entry.getResourceEntityAlias() == null || entry.getResourceEntityAlias().isBlank()) {
            return false;
        }
        return dynamicRecords.map(records -> {
            try {
                records.registry().requireEntity(entry.getResourceModuleAlias(), entry.getResourceEntityAlias());
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }).orElse(false);
    }

    @Override
    public Optional<SoftDeleteAbility<?>> resolve(DeletionEntry entry) {
        Objects.requireNonNull(entry, "deletionEntry must not be null");
        if (!supports(entry)) {
            return Optional.empty();
        }
        return dynamicRecords.map(records -> records.entityService(
                entry.getResourceModuleAlias(), entry.getResourceEntityAlias()));
    }
    @Override
    public boolean canPurgeAggregateChild(DeletionEntry entry, DeletionEntry parent) {
        if (parent == null || entry.getTriggerType() != DeletionEntryTrigger.CASCADE
                || !Objects.equals(entry.getParentEntryId(), parent.getId())
                || !Objects.equals(entry.getOperationId(), parent.getOperationId())
                || !Objects.equals(entry.getResourceModuleAlias(), parent.getResourceModuleAlias())
                || !Objects.equals(entry.getTenantId(), parent.getTenantId())) return false;
        DynamicRecordRuntime records = dynamicRecords.orElseThrow();
        var module = records.registry().modules().stream()
                .filter(candidate -> entry.getResourceModuleAlias().equals(candidate.moduleAlias())).findFirst().orElseThrow();
        var relations = module.relations().stream()
                .filter(candidate -> candidate.parentEntityAlias().equals(parent.getResourceEntityAlias())
                        && candidate.childEntityAlias().equals(entry.getResourceEntityAlias())
                        && candidate.cascadeOnParentUnavailable(module.moduleAlias(), module.references()))
                .toList();
        if (relations.isEmpty()) return false;
        var owner = records.entityService(module.moduleAlias(), parent.getResourceEntityAlias()).selectIgnoreSoftDelete(parent.getResourceRecordId());
        var child = records.entityService(module.moduleAlias(), entry.getResourceEntityAlias()).selectIgnoreSoftDelete(entry.getResourceRecordId());
        if (owner == null || child == null || !Boolean.TRUE.equals(owner.getDeleted())
                || !Boolean.TRUE.equals(child.getDeleted())
                || !Objects.equals(owner.getVersion(), parent.getResourceVersion())
                || !Objects.equals(child.getVersion(), entry.getResourceVersion())
                || !Objects.equals(owner.getTenantId(), parent.getTenantId())
                || !Objects.equals(child.getTenantId(), entry.getTenantId())
                || relations.stream().filter(relation -> Objects.equals(child.getValue(relation.childForeignKeyField()), owner.getId())).count() != 1) {
            throw new net.ximatai.muyun.spring.ability.OptimisticLockException(
                    "aggregate ownership or retained version changed after source deletion");
        }
        return true;
    }

}
