package net.ximatai.muyun.spring.platform.web;

/**
 * Standard source-management affordances which a page navigator may expose.
 *
 * <p>This is a presentation allow-list only.  It never grants a source-module
 * action; authorization and record availability remain owned by that source.</p>
 */
public enum PageNavigatorManagementAction {
    CREATE,
    UPDATE,
    DELETE
}
