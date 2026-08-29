package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Published change-set result; runtime activation is scheduled after transaction commit. */
public record MetadataRelationChangeSetPublishResult(
        MetadataRelationChangeSetPreview validatedPreview,
        ModuleMetadataCapabilitySnapshot snapshot,
        List<String> affectedModuleAliases
) {
}
