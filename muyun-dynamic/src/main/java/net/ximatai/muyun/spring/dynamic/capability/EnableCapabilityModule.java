package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Set;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;

/** Strongly typed facets owned by the ENABLE capability. */
public final class EnableCapabilityModule implements CapabilityModule {
    private final EnableCapabilityDefinitionFacet definition = new EnableCapabilityDefinitionFacet();
    private final EnableCapabilityActionFacet actions = new EnableCapabilityActionFacet();

    public EntityCapability capability() {
        return EntityCapability.ENABLE;
    }

    @Override
    public Set<EntityCapability> dependencies() {
        return Set.of(EntityCapability.CRUD);
    }

    @Override
    public void validateDynamicDefinition(EntityDefinition entity) {
        definition.validate(entity, entity.fields().stream()
                .filter(field -> PlatformAbilityFields.ENABLED_FIELD.equals(field.fieldName()))
                .findFirst().orElse(null));
    }

    public boolean isEnabledOnStaticService(Object service) {
        return service instanceof EnableAbility<?>;
    }

    public EnableCapabilityDefinitionFacet definition() {
        return definition;
    }

    public EnableCapabilityActionFacet actions() {
        return actions;
    }

    @Override
    public CapabilityActionContribution actionContribution() {
        return actions;
    }
}
