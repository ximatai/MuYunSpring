package net.ximatai.muyun.spring.ability.logging;

/**
 * One operator account that has produced at least one visible business-log event.
 *
 * <p>The tenant is retained for trusted IAM display enrichment. The selectable value is always
 * {@code operatorId}; this projection deliberately contains no current-directory authorization
 * facts.</p>
 */
public record BusinessLogOperatorCandidate(String tenantId, String operatorId, String operatorAccount) {
    public BusinessLogOperatorCandidate {
        tenantId = BusinessLogContext.optional(tenantId, "tenantId", 128);
        operatorId = BusinessLogContext.optional(operatorId, "operatorId", 128);
        operatorAccount = BusinessLogContext.optional(operatorAccount, "operatorAccount", 256);
        if (operatorId == null) {
            throw new IllegalArgumentException("operatorId must not be blank");
        }
    }

    public BusinessLogOperatorCandidate(String tenantId, String operatorId) {
        this(tenantId, operatorId, null);
    }
}
