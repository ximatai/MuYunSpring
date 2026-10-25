package net.ximatai.muyun.spring.common.platform;

import net.ximatai.muyun.spring.common.identity.CurrentUser;

public record ActionAuthorizationResult(
        String decision,
        String operatorId,
        String operatorType,
        String permissionCode,
        String permissionActionCode
) {
    public static final String DECISION_ALLOWED = "ALLOWED";
    public static final String OPERATOR_ANONYMOUS = "ANONYMOUS";
    public static final String OPERATOR_SYSTEM = "SYSTEM";
    public static final String OPERATOR_USER = "USER";
    public static final String OPERATOR_BACKGROUND_JOB = "BACKGROUND_JOB";

    public ActionAuthorizationResult {
        decision = requireText(decision, "decision");
        operatorId = normalize(operatorId);
        operatorType = normalize(operatorType);
        permissionCode = normalize(permissionCode);
        permissionActionCode = normalize(permissionActionCode);
    }

    public static ActionAuthorizationResult allowed(ActionExecutionContext context) {
        return allowed(context, DECISION_ALLOWED);
    }

    public static ActionAuthorizationResult allowed(ActionExecutionContext context, String decision) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return context.currentUser()
                .map(user -> new ActionAuthorizationResult(
                        decision,
                        user.userId(),
                        operatorType(user),
                        context.permissionCode(),
                        context.actionPolicy().permissionActionCode()
                ))
                .orElseGet(() -> new ActionAuthorizationResult(
                        decision,
                        null,
                        OPERATOR_ANONYMOUS,
                        context.permissionCode(),
                        context.actionPolicy().permissionActionCode()
                ));
    }

    private static String operatorType(CurrentUser user) {
        return user.system() ? OPERATOR_SYSTEM : OPERATOR_USER;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
