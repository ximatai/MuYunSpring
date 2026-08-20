package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityFacet;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityOperationContext;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;

import java.util.Set;
import java.util.Optional;

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
    public Optional<DynamicCapabilityDefinitionFacet> dynamicDefinitionFacet() {
        // The soft-delete fields are runtime-standard fields, rather than user-declared metadata fields.
        return Optional.of(entity -> { });
    }

    @Override
    public Optional<StaticCapabilityFacet> staticFacet() {
        return Optional.of(new StaticCapabilityFacet() {
            @Override public boolean supports(Object service) { return service instanceof RecycleBinAbility<?>; }
            @Override public java.util.List<net.ximatai.muyun.spring.ability.PlatformOperationDefinition> standardOperations(
                    StaticCapabilityOperationContext context) {
                return actions.staticOperations(((RecycleBinAbility<?>) context.service()).isRecycleBinPurgeEnabled());
            }
        });
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
