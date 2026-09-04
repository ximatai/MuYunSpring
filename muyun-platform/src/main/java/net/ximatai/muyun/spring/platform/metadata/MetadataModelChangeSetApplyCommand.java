package net.ximatai.muyun.spring.platform.metadata;

/** Apply request for one prior module-wide metadata model preview. */
public record MetadataModelChangeSetApplyCommand(
        MetadataModelChangeSetPreviewCommand proposal,
        String proposalFingerprint
) {
}
