package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.List;
import java.util.Map;

/** One existing relation's edits inside a module-wide metadata model proposal. */
public record MetadataModelRelationChangeSetDraft(
        String relationId,
        Integer expectedMetadataVersion,
        Map<EntityCapability, Boolean> capabilitySelections,
        List<MetadataFieldChangeSetDraft> fieldDrafts
) {
    MetadataRelationChangeSetPreviewCommand asRelationProposal() {
        return new MetadataRelationChangeSetPreviewCommand(expectedMetadataVersion, capabilitySelections, fieldDrafts);
    }
}
