package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Read-only capability facts for one metadata relation in a module. */
public record ModuleMetadataCapabilitySnapshot(
        String moduleAlias,
        String relationId,
        RelationRole relationRole,
        List<ModuleMetadataSystemFieldFact> systemFields,
        List<ModuleMetadataCapabilityFact> capabilities
) {
}
