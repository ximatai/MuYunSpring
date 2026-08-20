package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;

/** Dynamic-metadata adapter owned by a capability, deliberately outside the source-neutral module contract. */
public interface DynamicCapabilityDefinitionFacet {
    void validateDefinition(EntityDefinition entity);

    default void validateReferences(EntityDefinition entity) {
    }
}
