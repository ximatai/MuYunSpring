package net.ximatai.muyun.spring.ability.logging;

/**
 * Safe, stable display projection for the authenticated operator of an already-authorized log event.
 *
 * <p>{@code organizationId}/{@code organizationName} come from the event's
 * {@link BusinessLogContext#operatorOrganizationId()} snapshot. {@code departmentId}/
 * {@code departmentName} come from the current IAM employee binding and can therefore be absent
 * after deletion or change after a later transfer. This display projection must never be used for
 * log authorization.</p>
 */
public record BusinessLogOperatorIdentity(
        String employeeName,
        String username,
        String organizationId,
        String organizationName,
        String departmentId,
        String departmentName
) {
}
