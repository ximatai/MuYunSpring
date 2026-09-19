package net.ximatai.muyun.spring.platform.web;

/** Optional status feedback owned by a page action placement. */
public enum PageActionStatusMode {
    /** No persistent status feedback after invocation. */
    NONE,
    /** The successful invocation validates the current record or form input snapshot. */
    INPUT_VALIDATION
}
