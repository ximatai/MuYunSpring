package net.ximatai.muyun.spring.platform.metadata;

/** Apply request bound to a prior side-effect-free proposal fingerprint. */
public record MetadataRelationChangeSetApplyCommand(
        MetadataRelationChangeSetPreviewCommand proposal,
        String proposalFingerprint
) {
}
