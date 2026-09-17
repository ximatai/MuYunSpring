package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Resolves exactly one scoped model connection and protects its stored credential. */
@Service
public class AiModelConfigurationService extends AbstractAbilityService<AiModelConfiguration> implements
        SoftDeleteAbility<AiModelConfiguration>,
        EnableAbility<AiModelConfiguration>,
        CacheAbility<AiModelConfiguration>,
        QueryAbility<AiModelConfiguration>,
        FieldProtectionAbility<AiModelConfiguration> {
    public static final String MODULE_ALIAS = "platform.ai_model_configuration";

    private final AiModelPlatformSettingService platformSettingService;
    private final FieldCryptoProvider cryptoProvider;
    private final FieldSigner signer;

    public AiModelConfigurationService(BaseDao<AiModelConfiguration, String> dao,
                                       AiModelPlatformSettingService platformSettingService,
                                       ObjectProvider<FieldCryptoProvider> cryptoProvider,
                                       ObjectProvider<FieldSigner> signer) {
        super(MODULE_ALIAS, AiModelConfiguration.class, dao);
        this.platformSettingService = platformSettingService;
        this.cryptoProvider = cryptoProvider == null ? FieldCryptoProvider.UNAVAILABLE
                : cryptoProvider.getIfAvailable(() -> FieldCryptoProvider.UNAVAILABLE);
        this.signer = signer == null ? FieldSigner.UNAVAILABLE : signer.getIfAvailable(() -> FieldSigner.UNAVAILABLE);
    }

    @Override
    public FieldCryptoProvider fieldCryptoProvider() {
        return cryptoProvider;
    }

    @Override
    public FieldSigner fieldSigner() {
        return signer;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, AiModelConfiguration.class,
                java.util.List.of("id", "tenantId", "provider", "protocol", "modelId", "enabled", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("createdAt"));
    }

    @Override
    public void beforeInsert(AiModelConfiguration configuration) {
        assertTenantSelfServiceAllowed();
        normalize(configuration);
        if (findCurrentScopeConfiguration() != null) {
            throw new PlatformException("only one AI model configuration is allowed in one scope");
        }
        applyNewApiKey(configuration, null);
    }

    @Override
    public void beforeUpdate(AiModelConfiguration configuration, AiModelConfiguration existing) {
        assertTenantSelfServiceAllowed();
        if (existing == null) {
            throw new PlatformException("AI model configuration does not exist");
        }
        configuration.setTenantId(existing.getTenantId());
        normalize(configuration);
        applyNewApiKey(configuration, existing);
    }

    @Override
    public void afterSelect(AiModelConfiguration configuration) {
        configuration.setApiKeyConfigured(configuration.getApiKey() != null && !configuration.getApiKey().isBlank());
    }

    /** Resolves the current tenant's configuration first and never silently falls back on failure. */
    public AiModelConfiguration requireEffectiveConfiguration() {
        if (TenantContext.currentTenantId().isPresent()) {
            AiModelConfiguration tenant = findCurrentScopeConfiguration();
            if (tenant != null) {
                requireUsable(tenant, "tenant");
                return tenant;
            }
        }
        AiModelConfiguration platform = findGlobalConfiguration();
        requireUsable(platform, "platform");
        return platform;
    }

    public AiModelConfiguration requireCurrentScopeConfiguration() {
        AiModelConfiguration configuration = findCurrentScopeConfiguration();
        if (configuration == null) {
            throw new PlatformConfigurationException("AI model configuration is missing for current scope");
        }
        return configuration;
    }

    private void assertTenantSelfServiceAllowed() {
        if (TenantContext.currentTenantId().isPresent() && !platformSettingService.tenantRegistrationEnabled()) {
            throw new PlatformException("tenant AI model registration is disabled by platform policy");
        }
    }

    private void normalize(AiModelConfiguration configuration) {
        if (configuration.getProvider() == null) {
            throw new PlatformException("AI model provider must not be blank");
        }
        if (configuration.getProtocol() == null) {
            configuration.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        }
        if (configuration.getProtocol() != AiModelProtocol.OPENAI_COMPATIBLE) {
            throw new PlatformException("unsupported AI model protocol: " + configuration.getProtocol());
        }
        if (configuration.getModelId() == null || configuration.getModelId().isBlank()) {
            throw new PlatformException("AI model id must not be blank");
        }
        configuration.setModelId(configuration.getModelId().trim());
        if (configuration.getEnabled() == null) {
            configuration.setEnabled(Boolean.TRUE);
        }
    }

    private void applyNewApiKey(AiModelConfiguration configuration, AiModelConfiguration existing) {
        String input = configuration.getApiKeyInput();
        configuration.setApiKeyInput(null);
        if (input == null || input.isBlank()) {
            if (existing == null || existing.getApiKey() == null || existing.getApiKey().isBlank()) {
                throw new PlatformException("AI model API key must not be blank");
            }
            configuration.setApiKey(existing.getApiKey());
            return;
        }
        configuration.setApiKey(input.trim());
    }

    private AiModelConfiguration findCurrentScopeConfiguration() {
        return findOne(Criteria.of());
    }

    private AiModelConfiguration findGlobalConfiguration() {
        try (TenantContext.Scope ignored = TenantContext.system("resolve platform AI model configuration")) {
            return list(Criteria.of(), PageRequest.of(1, 2)).stream()
                    .filter(item -> item.getTenantId() == null || item.getTenantId().isBlank())
                    .findFirst()
                    .orElse(null);
        }
    }

    private void requireUsable(AiModelConfiguration configuration, String scope) {
        if (configuration == null) {
            throw new PlatformConfigurationException("no usable " + scope + " AI model configuration exists");
        }
        if (!Boolean.TRUE.equals(configuration.getEnabled())) {
            throw new PlatformConfigurationException(scope + " AI model configuration is disabled");
        }
        if (configuration.getApiKey() == null || configuration.getApiKey().isBlank()) {
            throw new PlatformConfigurationException(scope + " AI model configuration has no API key");
        }
    }
}
