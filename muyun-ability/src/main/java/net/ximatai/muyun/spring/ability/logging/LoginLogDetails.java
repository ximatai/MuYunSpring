package net.ximatai.muyun.spring.ability.logging;

/** Typed details for one authentication attempt. */
public record LoginLogDetails(
        String authenticationMethod,
        LoginOutcome outcome,
        String reasonCode,
        String sourceIp,
        String claimedAccount,
        String confirmedAccount
) implements BusinessLogDetails {
    public LoginLogDetails {
        authenticationMethod = BusinessLogContext.required(authenticationMethod, "authenticationMethod", 64);
        outcome = java.util.Objects.requireNonNull(outcome, "outcome must not be null");
        reasonCode = BusinessLogContext.optional(reasonCode, "reasonCode", 128);
        sourceIp = BusinessLogContext.optional(sourceIp, "sourceIp", 64);
        claimedAccount = BusinessLogContext.optional(claimedAccount, "claimedAccount", 256);
        confirmedAccount = BusinessLogContext.optional(confirmedAccount, "confirmedAccount", 256);
    }

    public enum LoginOutcome { SUCCESS, FAILURE }
}
