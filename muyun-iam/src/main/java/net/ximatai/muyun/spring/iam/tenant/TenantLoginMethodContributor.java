package net.ximatai.muyun.spring.iam.tenant;

import java.util.List;

/**
 * Optional capability that adds sign-in methods to a tenant's public login context. Implementations must
 * return nothing for unknown or inactive tenants, since the login context is read anonymously.
 */
public interface TenantLoginMethodContributor {
    List<TenantLoginMethod> loginMethods(String tenantId);
}
