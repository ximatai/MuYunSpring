package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reads legacy selected-tenant grants for the explicit ownership migration.
 * New runtime behavior must use {@link AiModelConfiguration#getTenantId()} instead.
 */
@Service
public class AiModelConfigurationTenantService extends AbstractAbilityService<AiModelConfigurationTenant> implements
        SoftDeleteAbility<AiModelConfigurationTenant>,
        ChildAbility<AiModelConfigurationTenant>,
        QueryAbility<AiModelConfigurationTenant> {
    public static final String MODULE_ALIAS = "platform.ai_model_configuration_tenant";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final AiModelConfigurationService configurationService;

    public AiModelConfigurationTenantService(BaseDao<AiModelConfigurationTenant, String> dao,
                                             AiModelConfigurationService configurationService) {
        super(MODULE_ALIAS, AiModelConfigurationTenant.class, dao);
        this.configurationService = configurationService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, AiModelConfigurationTenant.class,
                List.of("id", "configurationId", "targetTenantId", "createdAt", "updatedAt"));
    }

    @Override
    public void beforeInsert(AiModelConfigurationTenant grant) {
        normalizeAndValidate(grant);
    }

    @Override
    public void beforeUpdate(AiModelConfigurationTenant grant) {
        AiModelConfigurationTenant existing = selectIncludingDeleted(grant.getId());
        if (existing == null) throw new PlatformException("AI model configuration tenant grant does not exist");
        grant.setConfigurationId(existing.getConfigurationId());
        normalizeAndValidate(grant);
    }

    public List<String> configurationIdsForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return List.of();
        try (TenantContext.Scope ignored = TenantContext.system("resolve tenant AI model grants")) {
            return list(Criteria.of().eq("targetTenantId", tenantId.trim()), ALL).stream()
                    .map(AiModelConfigurationTenant::getConfigurationId).distinct().toList();
        }
    }

    /**
     * A removed grant may be selected again later. Restore its retained row so the aggregate
     * replacement lifecycle honors the database's configuration/tenant uniqueness invariant.
     */
    @Override
    public AiModelConfigurationTenant findDeletedReplacement(AiModelConfigurationTenant incoming) {
        if (incoming == null || incoming.getConfigurationId() == null || incoming.getTargetTenantId() == null) {
            return null;
        }
        return getDao().query(Criteria.of()
                        .eq("configurationId", incoming.getConfigurationId().trim())
                        .eq("targetTenantId", incoming.getTargetTenantId().trim()), ALL)
                .stream()
                .filter(grant -> Boolean.TRUE.equals(grant.getDeleted()))
                .findFirst()
                .orElse(null);
    }

    private void normalizeAndValidate(AiModelConfigurationTenant grant) {
        if (!TenantContext.isSystem()) {
            throw new PlatformException("AI model configuration tenant grants are only manageable in platform scope");
        }
        if (grant.getConfigurationId() == null || grant.getConfigurationId().isBlank()) {
            throw new PlatformException("AI model configuration id must not be blank");
        }
        if (grant.getTargetTenantId() == null || grant.getTargetTenantId().isBlank()) {
            throw new PlatformException("target tenant must not be blank");
        }
        AiModelConfiguration configuration = configurationService.select(grant.getConfigurationId().trim());
        if (configuration == null || configuration.getTenantId() != null) {
            throw new PlatformException("tenant grant requires a platform-owned AI model configuration");
        }
        grant.setConfigurationId(configuration.getId());
        grant.setTargetTenantId(grant.getTargetTenantId().trim());
        grant.setTenantId(null);
        rejectDuplicate(grant, Criteria.of().eq("configurationId", grant.getConfigurationId())
                        .eq("targetTenantId", grant.getTargetTenantId()),
                "target tenant is already granted this AI model configuration");
    }
}
