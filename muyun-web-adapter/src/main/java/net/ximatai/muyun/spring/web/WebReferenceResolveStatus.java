package net.ximatai.muyun.spring.web;

/** Outcome of a reference candidate query or translation. */
public enum WebReferenceResolveStatus {
    OK,
    RESOLVED,
    NOT_FOUND,
    AMBIGUOUS,
    PARTIAL
}
