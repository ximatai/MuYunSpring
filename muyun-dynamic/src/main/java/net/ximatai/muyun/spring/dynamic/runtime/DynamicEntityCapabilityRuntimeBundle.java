package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModule;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;

/** Closed runtime composition for the capability facets owned by one dynamic entity. */
final class DynamicEntityCapabilityRuntimeBundle {
    private final DynamicTreeRuntime tree;
    private final DynamicSortRuntime sort;
    private final DynamicReferenceRuntime reference;
    private final DynamicFormulaRuntime formula;
    private final EntityDefinition entity;
    private final CapabilityModuleRegistry registry;

    static DynamicEntityCapabilityRuntimeBundle create(DynamicEntityService owner,
                                                       String moduleAlias,
                                                       EntityDefinition entity,
                                                       ModuleDefinition module) {
        return new DynamicEntityCapabilityRuntimeBundle(owner, moduleAlias, entity, module,
                CapabilityModuleRegistry.defaultRegistry());
    }

    private DynamicEntityCapabilityRuntimeBundle(DynamicEntityService owner,
                                                 String moduleAlias,
                                                 EntityDefinition entity,
                                                 ModuleDefinition module,
                                                 CapabilityModuleRegistry registry) {
        this.entity = entity;
        this.registry = registry;
        this.tree = new DynamicTreeRuntime(owner);
        this.sort = new DynamicSortRuntime(owner);
        this.reference = new DynamicReferenceRuntime(owner);
        this.formula = new DynamicFormulaRuntime(moduleAlias, entity, module);
    }

    DynamicTreeRuntime tree() { require(EntityCapability.TREE); return tree; }
    DynamicSortRuntime sort() { require(EntityCapability.SORT); return sort; }
    DynamicReferenceRuntime reference() { require(EntityCapability.REFERENCE); return reference; }
    DynamicFormulaRuntime formula() { return formula; }

    void require(EntityCapability capability) {
        if (!entity.supports(capability)) {
            throw new PlatformException("dynamic entity does not support capability: " + capability);
        }
        registry.find(capability).ifPresent(module -> requireDependencies(module, capability));
    }

    private void requireDependencies(CapabilityModule module, EntityCapability requested) {
        for (EntityCapability dependency : module.dependencies()) {
            if (!entity.supports(dependency)) {
                throw new PlatformException("dynamic capability " + requested + " requires " + dependency
                        + " capability: " + entity.alias());
            }
        }
    }
}
