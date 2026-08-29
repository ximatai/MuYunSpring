package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.List;
import java.util.Set;

/** Deterministic, side-effect-free preview of a final relation metadata proposal. */
public record MetadataRelationChangeSetPreview(
        String moduleAlias,
        String relationId,
        String metadataId,
        Integer metadataVersion,
        Set<EntityCapability> effectiveCapabilities,
        List<MetadataChangeSetFieldImpact> fieldImpacts,
        List<MetadataChangeSetSchemaImpact> schemaImpacts,
        List<MetadataChangeSetValidationIssue> warnings,
        List<MetadataChangeSetValidationIssue> errors,
        String proposalFingerprint,
        MetadataRelationChangeSetPlan plan
) {
    /** Compatibility constructor for callers that only consume the public preview projection. */
    public MetadataRelationChangeSetPreview(String moduleAlias, String relationId, String metadataId,
                                            Integer metadataVersion, Set<EntityCapability> effectiveCapabilities,
                                            List<MetadataChangeSetFieldImpact> fieldImpacts,
                                            List<MetadataChangeSetSchemaImpact> schemaImpacts,
                                            List<MetadataChangeSetValidationIssue> warnings,
                                            List<MetadataChangeSetValidationIssue> errors,
                                            String proposalFingerprint) {
        this(moduleAlias, relationId, metadataId, metadataVersion, effectiveCapabilities, fieldImpacts, schemaImpacts,
                warnings, errors, proposalFingerprint,
                new MetadataRelationChangeSetPlan(metadataId, metadataVersion, effectiveCapabilities, false, List.of()));
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}
