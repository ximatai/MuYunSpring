package net.ximatai.muyun.spring.iam.tenant;

/** A sign-in method a tenant offers on its locked login entry in addition to the password. */
public record TenantLoginMethod(String providerId, String title, String providerType) {
}
