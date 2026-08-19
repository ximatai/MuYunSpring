package net.ximatai.muyun.spring.common.platform;

public interface ActionExecutionPolicyService {
    void requireAuthorized(ActionExecutionContext context);

    default ActionAuthorizationResult authorize(ActionExecutionContext context) {
        requireAuthorized(context);
        return ActionAuthorizationResult.allowed(context);
    }

    /**
     * Authorizes the action itself without exposing record ids. Record data scope is a separate
     * concern and must be evaluated by the caller against the requested records.
     */
    default ActionAuthorizationResult authorizeAction(String moduleAlias,
                                                      ActionExecutionPolicy policy,
                                                      java.util.Optional<net.ximatai.muyun.spring.common.identity.CurrentUser> currentUser) {
        return authorize(ActionExecutionContext.ofPolicy(moduleAlias, policy, java.util.Set.of(), currentUser));
    }

    default void requireRecordAction(ActionExecutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("action execution context must not be null");
        }
        if (!context.hasRecordContext()) {
            throw new IllegalArgumentException("record action requires record id context: "
                    + context.moduleAlias() + "." + context.actionCode());
        }
        authorize(context);
    }
}
