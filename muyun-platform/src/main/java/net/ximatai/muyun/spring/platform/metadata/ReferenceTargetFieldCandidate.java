package net.ximatai.muyun.spring.platform.metadata;

/** One safe, metadata-backed field that may be selected for a reference target contract. */
public record ReferenceTargetFieldCandidate(
        String fieldName,
        String title,
        boolean defaultField,
        boolean selectable
) {
}
