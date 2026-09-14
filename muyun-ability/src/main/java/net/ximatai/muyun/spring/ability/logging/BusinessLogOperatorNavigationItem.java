package net.ximatai.muyun.spring.ability.logging;

/** One event-time scope value available in an already-authorized log-operator picker. */
public record BusinessLogOperatorNavigationItem(String tenantId, String organizationId, String id) {
    public BusinessLogOperatorNavigationItem {
        tenantId = BusinessLogContext.optional(tenantId, "tenantId", 128);
        organizationId = BusinessLogContext.optional(organizationId, "organizationId", 128);
        id = BusinessLogContext.optional(id, "id", 128);
        if (id == null) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    public BusinessLogOperatorNavigationItem(String tenantId, String id) {
        this(tenantId, null, id);
    }
}
