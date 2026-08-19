package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;

import java.util.Set;

/** Strongly typed TREE capability: parent hierarchy plus an explicit SORT dependency. */
public final class TreeCapabilityModule implements CapabilityModule {
    private final TreeCapabilityDefinitionFacet definition = new TreeCapabilityDefinitionFacet();
    private final TreeCapabilityActionFacet actions = new TreeCapabilityActionFacet();

    @Override
    public EntityCapability capability() {
        return EntityCapability.TREE;
    }

    @Override
    public Set<EntityCapability> dependencies() {
        return Set.of(EntityCapability.CRUD, EntityCapability.SORT);
    }

    @Override
    public void validateDynamicDefinition(EntityDefinition entity) {
        definition.validate(entity);
    }

    public boolean isEnabledOnStaticService(Object service) {
        return service instanceof TreeAbility<?>;
    }

    public TreeCapabilityDefinitionFacet definition() {
        return definition;
    }

    public TreeCapabilityActionFacet actions() {
        return actions;
    }

    @Override
    public CapabilityActionContribution actionContribution() {
        return actions;
    }
}
