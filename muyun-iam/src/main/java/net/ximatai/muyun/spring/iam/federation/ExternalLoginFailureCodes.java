package net.ximatai.muyun.spring.iam.federation;

/** Stable denial codes of external login transactions; clients show messages, never internal details. */
public final class ExternalLoginFailureCodes {
    public static final String IDENTITY_NOT_BOUND = "IDENTITY_NOT_BOUND";
    public static final String IDENTITY_CONFLICT = "IDENTITY_CONFLICT";
    public static final String ALREADY_BOUND = "ALREADY_BOUND";
    public static final String ENTERPRISE_MISMATCH = "ENTERPRISE_MISMATCH";
    public static final String POLICY_DISABLED = "POLICY_DISABLED";
    public static final String ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";
    public static final String PROVIDER_REJECTED = "PROVIDER_REJECTED";
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    public static final String STATE_INVALID = "STATE_INVALID";

    private ExternalLoginFailureCodes() {
    }
}
