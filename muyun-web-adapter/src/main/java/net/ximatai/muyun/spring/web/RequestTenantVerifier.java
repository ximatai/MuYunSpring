package net.ximatai.muyun.spring.web;

/** Verifies a requested business tenant against the authenticated identity and tenant availability. */
@FunctionalInterface
public interface RequestTenantVerifier {
    void verify(String tenantId);
}
