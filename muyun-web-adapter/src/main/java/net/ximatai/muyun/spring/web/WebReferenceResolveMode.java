package net.ximatai.muyun.spring.web;

/**
 * The operation requested from a field reference. This is intentionally a web
 * contract: static and metadata-backed modules expose the same vocabulary.
 */
public enum WebReferenceResolveMode {
    QUERY,
    TREE,
    /**
     * Reads one tree level for a field reference. The requested parent is evaluated in the same
     * candidate and REFERENCE scope as its children, so a browser cannot use a tree expansion to
     * widen the reference range.
     */
    TREE_CHILDREN,
    TRANSLATE
}
