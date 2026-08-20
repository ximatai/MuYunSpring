package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityFacet;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityDeclarationPolicy;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityOperationContext;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;

import java.util.Set;
import java.util.Optional;

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
    public StaticCapabilityDeclarationPolicy declarationPolicy() {
        return StaticCapabilityDeclarationPolicy.SERVICE_ONLY;
    }

    @Override
    public Optional<DynamicCapabilityDefinitionFacet> dynamicDefinitionFacet() {
        return Optional.of(new DynamicCapabilityDefinitionFacet() {
            @Override public void validateDefinition(EntityDefinition entity) { definition.validate(entity); }
            @Override public void validateReferences(EntityDefinition entity) { definition.validateReferences(entity); }
        });
    }

    @Override
    public Optional<StaticCapabilityFacet> staticFacet() {
        return Optional.of(new StaticCapabilityFacet() {
            @Override public boolean supports(Object service) { return service instanceof SortAbility<?>; }
            @Override public java.util.List<net.ximatai.muyun.spring.ability.PlatformOperationDefinition> standardOperations(
                    StaticCapabilityOperationContext context) {
                return context.supports(EntityCapability.TREE) ? java.util.List.of() : actions.staticOperations();
            }
        });
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
