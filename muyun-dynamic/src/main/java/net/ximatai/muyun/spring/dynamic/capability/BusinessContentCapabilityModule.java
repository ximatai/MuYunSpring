package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.capability.*;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.child.StaticChildResolver;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import java.util.*;

/** Field-owned content declarations publish independent maintenance and quality-confirmation rights. */
public final class BusinessContentCapabilityModule implements CapabilityModule, CapabilityActionContribution {
    public EntityCapability capability() { return EntityCapability.BUSINESS_CONTENT; }
    public Set<EntityCapability> dependencies() { return Set.of(EntityCapability.CRUD); }
    public CapabilityActionContribution actionContribution() { return this; }
    public StaticCapabilityDeclarationPolicy declarationPolicy() { return StaticCapabilityDeclarationPolicy.SERVICE_ONLY; }
    public List<PlatformAction> standardActions() { return List.of(PlatformAction.CONTENT_EDIT, PlatformAction.CONTENT_CONFIRM); }
    public Optional<StaticCapabilityFacet> staticFacet() {
        return Optional.of(new StaticCapabilityFacet() {
            public boolean supports(Object service) {
                if (!(service instanceof CrudAbility<?> crud) || crud.modelClass() == null) return false;
                if (!StaticContentFieldDefinitionCompiler.resolve(crud.modelClass()).isEmpty()) return true;
                return service instanceof ChildrenAbility<?> && StaticChildResolver.rules(crud.modelClass()).stream()
                        .anyMatch(rule -> declaresContent(rule.childModel(), new HashSet<>()));
            }
            public List<PlatformAction> standardActions(StaticCapabilityOperationContext context) {
                return supports(context.service()) ? BusinessContentCapabilityModule.this.standardActions() : List.of();
            }
        });
    }
    private boolean declaresContent(Class<?> model, Set<Class<?>> visited) {
        if (!visited.add(model)) return false;
        if (!StaticContentFieldDefinitionCompiler.resolve(model).isEmpty()) return true;
        // Compile producer-owned model facts; child service registration belongs to runtime composition.
        return StaticChildResolver.rules(model).stream().anyMatch(rule -> declaresContent(rule.childModel(), visited));
    }
    /** Only actual text declarations and their owned aggregate ancestry establish the capability. */
    public static Set<String> aggregateOwners(List<EntityDefinition> entities, List<EntityRelationDefinition> relations) {
        var owners = new HashSet<String>();
        entities.stream().filter(entity -> entity.fields().stream().anyMatch(field -> field.content().enabled()))
                .map(EntityDefinition::alias).forEach(owners::add);
        boolean changed;
        do {
            changed = false;
            for (var relation : relations) if (owners.contains(relation.childEntityAlias()))
                changed |= owners.add(relation.parentEntityAlias());
        } while (changed);
        return Set.copyOf(owners);
    }

    // Shared record content routes own these HTTP workflows; they do not execute business mutations.
    public boolean isHttpOnlyDynamicAction(PlatformAction action) { return true; }
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action) { return Optional.empty(); }
}
