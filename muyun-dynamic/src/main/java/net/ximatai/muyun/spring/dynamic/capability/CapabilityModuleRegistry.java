package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
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
public final class CapabilityModuleRegistry {
    private static final CapabilityModuleRegistry DEFAULT = new CapabilityModuleRegistry(List.of(
            new EnableCapabilityModule(), new SortCapabilityModule(), new TreeCapabilityModule(),
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

    public Optional<CapabilityActionContribution> actionOwner(PlatformAction action) {
        return Optional.ofNullable(actionContributions.get(action));
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
            module.validateDynamicReferences(entity);
            if (!entity.supports(module.capability())) {
                continue;
            }
            for (EntityCapability dependency : module.dependencies()) {
                if (!entity.supports(dependency)) {
                    throw new ModuleDefinitionException(module.capability() + " capability requires "
                            + dependency + " capability: " + entity.alias());
                }
            }
            module.validateDynamicDefinition(entity);
        }
    }
}
