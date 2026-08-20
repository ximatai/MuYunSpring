package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityRegistry;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

/**
 * Typed ownership boundary for platform capabilities.
 *
 * <p>This is deliberately not an open-ended SPI: a capability is added here only after its
 * definition, action and runtime facets have a stable shared contract. ENABLE is the first
 * complete module and provides the template for subsequent capabilities.</p>
 */
public final class CapabilityModuleRegistry implements StaticCapabilityRegistry {
    private static final CapabilityModuleRegistry DEFAULT = new CapabilityModuleRegistry(List.of(
            // Stable declaration order also preserves the long-standing static action order:
            // tree/sort operations precede enablement operations in generated module contracts.
            new SortCapabilityModule(), new TreeCapabilityModule(), new EnableCapabilityModule(),
            new RecycleBinCapabilityModule()));

    private final Map<EntityCapability, CapabilityModule> modules;
    private final List<CapabilityModule> registeredModules;
    private final Map<PlatformAction, CapabilityActionContribution> actionContributions;

    public CapabilityModuleRegistry(List<? extends CapabilityModule> registeredModules) {
        List<? extends CapabilityModule> values = registeredModules == null ? List.of() : List.copyOf(registeredModules);
        this.registeredModules = List.copyOf(values);
        this.modules = values.stream().collect(Collectors.toUnmodifiableMap(CapabilityModule::capability,
                Function.identity(), (left, right) -> {
                    throw new IllegalStateException("duplicate capability module registration: " + left.capability());
                }));
        this.actionContributions = values.stream().flatMap(module -> module.actionContribution().standardActions().stream()
                        .map(action -> Map.entry(action, module.actionContribution())))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> {
                    throw new IllegalStateException("duplicate capability action contribution");
                }));
        for (CapabilityModule module : values) {
            if (module.dependencies().contains(module.capability())) {
                throw new IllegalStateException("capability module must not depend on itself: " + module.capability());
            }
        }
    }

    public static CapabilityModuleRegistry defaultRegistry() {
        return DEFAULT;
    }

    public Optional<CapabilityModule> find(EntityCapability capability) {
        return Optional.ofNullable(modules.get(capability));
    }

    /** Closed registered modules, exposed only for platform composition of their typed facets. */
    public List<CapabilityModule> modules() {
        return registeredModules;
    }

    @Override
    public List<CapabilityModule> staticModules() {
        return registeredModules;
    }

    public Optional<CapabilityActionContribution> actionOwner(PlatformAction action) {
        return Optional.ofNullable(actionContributions.get(action));
    }

    /**
     * Resolves static execution ownership. TREE deliberately owns SORT when its service contract
     * is present, while the action's HTTP/OpenAPI facts remain owned by SORT.
     */
    public Optional<CapabilityActionContribution> staticActionOwner(PlatformAction action, Object staticService) {
        if (action == PlatformAction.SORT) {
            TreeCapabilityModule tree = require(EntityCapability.TREE, TreeCapabilityModule.class);
            if (tree.isEnabledOnStaticService(staticService)) {
                return Optional.of(tree.actionContribution());
            }
        }
        return actionOwner(action);
    }

    /** TREE explicitly owns the dynamic-web SORT-to-placement bridge when TREE is declared. */
    public Optional<CapabilityActionContribution> dynamicWebActionOwner(PlatformAction action,
                                                                         java.util.Set<String> capabilityNames) {
        if (action == PlatformAction.SORT
                && capabilityNames != null
                && capabilityNames.contains(EntityCapability.TREE.name())) {
            return Optional.of(require(EntityCapability.TREE, TreeCapabilityModule.class).actionContribution());
        }
        return actionOwner(action);
    }

    public <T extends CapabilityModule> T require(EntityCapability capability, Class<T> moduleType) {
        CapabilityModule module = find(capability)
                .orElseThrow(() -> new IllegalArgumentException("capability module is not registered: " + capability));
        if (!moduleType.isInstance(module)) {
            throw new IllegalStateException("capability module type does not match " + capability + ": "
                    + module.getClass().getName());
        }
        return moduleType.cast(module);
    }

    /** Validates capability ownership/dependencies and the owned dynamic-definition contract. */
    public void validate(EntityDefinition entity) {
        for (CapabilityModule module : modules.values()) {
            module.dynamicDefinitionFacet().ifPresent(facet -> facet.validateReferences(entity));
            if (!entity.supports(module.capability())) {
                continue;
            }
            for (EntityCapability dependency : module.dependencies()) {
                if (!entity.supports(dependency)) {
                    throw new ModuleDefinitionException(module.capability() + " capability requires "
                            + dependency + " capability: " + entity.alias());
                }
            }
            module.dynamicDefinitionFacet().ifPresent(facet -> facet.validateDefinition(entity));
        }
    }
}
