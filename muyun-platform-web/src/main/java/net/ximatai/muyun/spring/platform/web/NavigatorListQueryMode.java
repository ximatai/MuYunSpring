package net.ximatai.muyun.spring.platform.web;

/**
 * Determines whether a navigator selection is a prerequisite for the page list query or an
 * optional filter applied when a selection exists.
 */
public enum NavigatorListQueryMode {
    REQUIRED_SCOPE,
    OPTIONAL_FILTER
}
