package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.query.*;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@ConditionalOnExternalIdentityFederation
@DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE})
public class DirectorySyncRunService extends TenantActiveScopedService<DirectorySyncRun> implements QueryAbility<DirectorySyncRun> {
    public static final String MODULE_ALIAS = "iam.directory_sync_run";
    public DirectorySyncRunService(DirectorySyncRunDao dao, ActiveTenantVerifier tenants) { super(MODULE_ALIAS, DirectorySyncRun.class, dao, tenants); }
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, DirectorySyncRun.class,
                List.of("id", "jobRunId", "policyId", "scopeId", "mode", "status", "completed", "createdAt"), Sort.desc("createdAt"));
    }
}
