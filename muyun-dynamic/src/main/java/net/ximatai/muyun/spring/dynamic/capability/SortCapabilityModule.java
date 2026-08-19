package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;

import java.util.Set;

/** Strongly typed facets owned by SORT; TREE reuses this contract as its explicit sort dependency. */
public final class SortCapabilityModule implements CapabilityModule {
    private final SortCapabilityDefinitionFacet definition = new SortCapabilityDefinitionFacet();
    private final SortCapabilityActionFacet actions = new SortCapabilityActionFacet();

    @Override
    public EntityCapability capability() {
        return EntityCapability.SORT;
    }

    @Override
    public Set<EntityCapability> dependencies() {
        return Set.of(EntityCapability.CRUD);
    }

    @Override
    public void validateDynamicDefinition(EntityDefinition entity) {
        definition.validate(entity);
    }

    @Override
    public void validateDynamicReferences(EntityDefinition entity) {
        definition.validateReferences(entity);
    }

    public boolean isEnabledOnStaticService(Object service) {
        return service instanceof SortAbility<?>;
    }

    public SortCapabilityDefinitionFacet definition() {
        return definition;
    }

    public SortCapabilityActionFacet actions() {
        return actions;
    }

    @Override
    public CapabilityActionContribution actionContribution() {
        return actions;
    }
}
