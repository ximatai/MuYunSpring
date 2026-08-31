package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Relation-scoped catalog for configuring a reference target; it deliberately contains no records. */
public record ReferenceTargetFieldCatalog(
        String targetModuleAlias,
        String targetMetadataId,
        List<ReferenceTargetFieldCandidate> keyFields,
        List<ReferenceTargetFieldCandidate> labelFields
) {
    public ReferenceTargetFieldCatalog {
        keyFields = keyFields == null ? List.of() : List.copyOf(keyFields);
        labelFields = labelFields == null ? List.of() : List.copyOf(labelFields);
    }
}
