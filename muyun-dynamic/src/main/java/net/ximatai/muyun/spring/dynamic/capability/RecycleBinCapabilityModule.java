package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;

import java.util.Set;

/** Typed RECYCLE_BIN module: retained-data lifecycle is explicit and always built on the runtime soft-delete kernel. */
public final class RecycleBinCapabilityModule implements CapabilityModule {
    private final RecycleBinCapabilityActionFacet actions = new RecycleBinCapabilityActionFacet();

    @Override
    public EntityCapability capability() {
        return EntityCapability.RECYCLE_BIN;
    }

    @Override
    public Set<EntityCapability> dependencies() {
        // Dynamic entities have the standard deleted/deletedAt runtime fields even though SOFT_DELETE
        // is not a separately declared metadata capability.
        return Set.of(EntityCapability.CRUD);
    }

    @Override
    public void validateDynamicDefinition(EntityDefinition entity) {
        // The soft-delete fields are runtime-standard fields, rather than user-declared metadata fields.
    }

    public boolean isEnabledOnStaticService(Object service) {
        return service instanceof RecycleBinAbility<?>;
    }

    public RecycleBinCapabilityActionFacet actions() {
        return actions;
    }

    @Override
    public CapabilityActionContribution actionContribution() {
        return actions;
    }
}
