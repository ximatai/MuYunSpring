package net.ximatai.muyun.spring.ability.logging;

/**
 * Event-attribution key for enriching one already-authorized business-log fact.
 *
 * <p>{@code operatorOrganizationId} is the immutable event-time authorization snapshot. It is
 * part of the key so one operator moved between organizations can still be displayed against the
 * organization recorded for each event.</p>
 */
public record BusinessLogOperatorIdentityKey(String tenantId, String operatorId, String operatorOrganizationId) {
    public BusinessLogOperatorIdentityKey {
        tenantId = BusinessLogContext.optional(tenantId, "tenantId", 128);
        operatorId = BusinessLogContext.optional(operatorId, "operatorId", 128);
        operatorOrganizationId = BusinessLogContext.optional(operatorOrganizationId,
                "operatorOrganizationId", 128);
        if (operatorId == null) {
            throw new IllegalArgumentException("operatorId must not be blank");
        }
    }
}
