package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Published result for one complete module metadata model change-set. */
public record MetadataModelChangeSetPublishResult(
        MetadataModelChangeSetPreview validatedPreview,
        List<ModuleMetadataCapabilitySnapshot> snapshots,
        List<String> affectedModuleAliases
) {
}
