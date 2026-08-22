package net.ximatai.muyun.spring.platform.web;

/**
 * Declares which standardized transport owns a reference field's candidates.
 * TARGET_NAVIGATOR is the lightweight default; SOURCE_FIELD preserves rules
 * declared by the source reference, such as filters and affect patches.
 */
public enum ReferenceCandidateDelivery {
    TARGET_NAVIGATOR,
    SOURCE_FIELD
}
