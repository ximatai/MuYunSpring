package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.query.*;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@ConditionalOnExternalIdentityFederation
@DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE})
public class JoinApplicationService extends TenantActiveScopedService<JoinApplication> implements QueryAbility<JoinApplication> {
    public static final String MODULE_ALIAS = "iam.join_application";
    private final ExternalProvisioningService provisioning;
    public JoinApplicationService(JoinApplicationDao dao, ActiveTenantVerifier tenants, ExternalProvisioningService provisioning) {
        super(MODULE_ALIAS, JoinApplication.class, dao, tenants); this.provisioning = java.util.Objects.requireNonNull(provisioning, "provisioning");
    }
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, JoinApplication.class,
                List.of("id", "providerId", "externalUserId", "displayName", "status", "reviewedAt", "createdAt"), Sort.desc("createdAt"));
    }
    public void review(String id, boolean approve, String note) {
        var row = select(id);
        if (row == null) throw ExternalProvisioningService.rejected();
        provisioning.review(row, approve, note, reviewed -> {
            if (update(reviewed) != 1) throw new IllegalStateException("Join application changed during review");
        });
    }
}
