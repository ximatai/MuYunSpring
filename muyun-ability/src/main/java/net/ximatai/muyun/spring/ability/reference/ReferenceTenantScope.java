package net.ximatai.muyun.spring.ability.reference;

/**
 * Tenant boundary of a reference declared by a business model.
 *
 * <p>Tenant-owned business references inherit the tenant of the record being
 * edited. Platform-wide targets can opt out explicitly. Cross-tenant business
 * references must not be introduced as an accidental consequence of a missing
 * scope; they require a dedicated capability when one is genuinely needed.</p>
 */
public enum ReferenceTenantScope {
    /** Resolve candidates and value translation inside the persisted source record's tenant. */
    SAME_TENANT,
    /** Resolve through the target's own scope, for platform-wide targets such as tenants or shared dictionaries. */
    GLOBAL
}
