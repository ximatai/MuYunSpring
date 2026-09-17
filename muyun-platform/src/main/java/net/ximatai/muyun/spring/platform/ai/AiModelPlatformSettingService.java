package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** Owns the single global policy that enables tenant model self-service. */
@Service
public class AiModelPlatformSettingService extends AbstractAbilityService<AiModelPlatformSetting>
        implements SoftDeleteAbility<AiModelPlatformSetting> {
    public static final String MODULE_ALIAS = "platform.ai_model_setting";

    public AiModelPlatformSettingService(BaseDao<AiModelPlatformSetting, String> dao) {
        super(MODULE_ALIAS, AiModelPlatformSetting.class, dao);
    }

    @Override
    public void beforeInsert(AiModelPlatformSetting setting) {
        requireGlobalScope();
        if (resolveCurrent() != null) {
            throw new PlatformException("AI model platform setting already exists");
        }
        normalize(setting);
    }

    @Override
    public void beforeUpdate(AiModelPlatformSetting setting, AiModelPlatformSetting existing) {
        requireGlobalScope();
        if (existing == null || existing.getTenantId() != null) {
            throw new PlatformException("AI model platform setting must stay global");
        }
        normalize(setting);
    }

    public boolean tenantRegistrationEnabled() {
        AiModelPlatformSetting setting = resolveCurrent();
        return setting != null && Boolean.TRUE.equals(setting.getTenantRegistrationEnabled());
    }

    public AiModelPlatformSetting resolveCurrent() {
        try (TenantContext.Scope ignored = TenantContext.system("resolve AI model platform setting")) {
            return findOne(Criteria.of());
        }
    }

    private void requireGlobalScope() {
        if (TenantContext.currentTenantId().isPresent()) {
            throw new PlatformException("AI model platform setting is only configurable in platform scope");
        }
    }

    private void normalize(AiModelPlatformSetting setting) {
        setting.setTenantId(null);
        if (setting.getTenantRegistrationEnabled() == null) {
            setting.setTenantRegistrationEnabled(Boolean.FALSE);
        }
    }
}
