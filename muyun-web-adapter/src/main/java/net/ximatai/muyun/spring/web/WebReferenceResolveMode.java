package net.ximatai.muyun.spring.web;

/**
 * The operation requested from a field reference. This is intentionally a web
 * contract: static and metadata-backed modules expose the same vocabulary.
 */
public enum WebReferenceResolveMode {
    QUERY,
    TREE,
    TRANSLATE
}
