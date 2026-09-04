package net.ximatai.muyun.spring.platform.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** Deterministic, side-effect-free preview for the complete dynamic module metadata model. */
public record MetadataModelChangeSetPreview(
        String moduleAlias,
        List<MetadataRelationChangeSetPreview> relationPreviews,
        List<MetadataChangeSetFieldImpact> fieldImpacts,
        List<MetadataChangeSetSchemaImpact> schemaImpacts,
        List<MetadataModelChangeSetOrderImpact> orderImpacts,
        List<MetadataChangeSetValidationIssue> warnings,
        List<MetadataChangeSetValidationIssue> errors,
        String proposalFingerprint,
        @JsonIgnore MetadataModelChangeSetPlan plan
) {
    /** Compatibility constructor for callers that do not consume the aggregated impact projection. */
    public MetadataModelChangeSetPreview(String moduleAlias,
                                         List<MetadataRelationChangeSetPreview> relationPreviews,
                                         List<MetadataChangeSetValidationIssue> warnings,
                                         List<MetadataChangeSetValidationIssue> errors,
                                         String proposalFingerprint,
                                         MetadataModelChangeSetPlan plan) {
        this(moduleAlias, relationPreviews, List.of(), List.of(), List.of(), warnings, errors, proposalFingerprint, plan);
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}
