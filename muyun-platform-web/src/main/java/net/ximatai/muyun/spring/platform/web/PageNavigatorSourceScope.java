package net.ximatai.muyun.spring.platform.web;

/**
 * Session-derived scope applied by a navigator source before downstream bindings.
 *
 * <p>{@link #CURRENT_TENANT} fixes the source to the authenticated tenant for a tenant user.
 * A system user has no authenticated tenant; its tenant navigator source then resolves records
 * through the standard REFERENCE authorization, allowing an explicitly selected tenant to drive
 * downstream bindings.</p>
 */
public enum PageNavigatorSourceScope {
    NONE,
    CURRENT_TENANT
}
