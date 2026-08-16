package net.ximatai.muyun.spring.platform.web;

/** Authoritative origin of a page-context value. */
public enum PageContextSource {
    SESSION,
    ROUTE,
    NAVIGATOR,
    FORM_FIELD
}
