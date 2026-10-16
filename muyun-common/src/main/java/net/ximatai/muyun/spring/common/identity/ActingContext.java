package net.ximatai.muyun.spring.common.identity;

public record ActingContext(
        String delegationId,
        CurrentUser operator,
        BusinessPrincipal principal,
        String moduleAlias,
        String actionCode,
        String delegatePositionId
) {
    public ActingContext(String delegationId, CurrentUser operator, BusinessPrincipal principal,
                         String moduleAlias, String actionCode) {
        this(delegationId, operator, principal, moduleAlias, actionCode, null);
    }

    public ActingContext {
        delegationId = requireText(delegationId, "delegationId");
        operator = java.util.Objects.requireNonNull(operator, "operator must not be null");
        principal = java.util.Objects.requireNonNull(principal, "principal must not be null");
        moduleAlias = normalize(moduleAlias);
        actionCode = normalize(actionCode);
        delegatePositionId = normalize(delegatePositionId);
    }

    public boolean matches(String moduleAlias, String actionCode) {
        String normalizedModule = normalize(moduleAlias);
        String normalizedAction = normalize(actionCode);
        return (this.moduleAlias == null || this.moduleAlias.equals(normalizedModule))
                && (this.actionCode == null || this.actionCode.equals(normalizedAction));
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
