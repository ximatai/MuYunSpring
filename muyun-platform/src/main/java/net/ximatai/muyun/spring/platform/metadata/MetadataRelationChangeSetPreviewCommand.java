package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.List;
import java.util.Map;

/**
 * Read-only proposal for one module metadata relation. It is not an authorization token and
 * must be revalidated by a future atomic release command.
 */
public record MetadataRelationChangeSetPreviewCommand(
        Integer expectedMetadataVersion,
        Map<EntityCapability, Boolean> capabilitySelections,
        List<MetadataFieldChangeSetDraft> fieldDrafts
) {
}
