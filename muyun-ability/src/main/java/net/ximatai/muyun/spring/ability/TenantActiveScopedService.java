package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;

import java.util.Objects;

/**
 * Standard business Service for records governed by the active tenant scope.
 *
 * <p>Combines the save validation templates of {@link StandardBusinessService} with
 * {@link TenantActiveScopedAbility}. Supply the host's active-tenant verifier and retain
 * the standard mutation chain; additional model capabilities remain explicit choices.</p>
 *
 * @param <T> tenant business model with the platform entity contract
 */
public abstract class TenantActiveScopedService<T extends EntityContract> extends StandardBusinessService<T>
        implements TenantActiveScopedAbility<T> {
    private final ActiveTenantVerifier activeTenantVerifier;

    protected TenantActiveScopedService(
            String moduleAlias,
            Class<T> modelClass,
            BaseDao<T, String> dao,
            ActiveTenantVerifier activeTenantVerifier
    ) {
        super(moduleAlias, modelClass, dao);
        this.activeTenantVerifier = Objects.requireNonNull(
                activeTenantVerifier,
                "activeTenantVerifier must not be null"
        );
    }

    @Override
    // Must remain interceptable: Spring class proxies delegate to the initialized
    // service target rather than reading fields on the constructor-bypassed proxy.
    public void verifyActiveTenant(String tenantId) {
        activeTenantVerifier.verifyActiveTenant(tenantId);
    }
}
