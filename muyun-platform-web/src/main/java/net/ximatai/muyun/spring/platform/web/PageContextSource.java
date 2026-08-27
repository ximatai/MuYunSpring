package net.ximatai.muyun.spring.platform.web;

/** Authoritative origin of a page-context value. */
public enum PageContextSource {
    SESSION,
    /**
     * An opaque browser selection that is re-resolved and authorized by the server for every request.
     * It is deliberately distinct from {@link #NAVIGATOR}: a navigator value remains browser workspace state.
     */
    RESOLVED_SELECTION,
    NAVIGATOR
}
