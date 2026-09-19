package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Resolves the enabled tenant configuration, then an explicitly exposed platform fallback. */
@Service
public class AiModelConfigurationService extends AbstractAbilityService<AiModelConfiguration> implements
        EnableAbility<AiModelConfiguration>,
        CacheAbility<AiModelConfiguration>,
        QueryAbility<AiModelConfiguration>,
        FieldProtectionAbility<AiModelConfiguration>,
        ReferenceAbility<AiModelConfiguration> {
    public static final String MODULE_ALIAS = "platform.ai_model_configuration";
    private static final ReferenceTarget TENANT_TARGET = ReferenceTarget.of("iam", "tenant");

    private final AiModelProviderService providerService;
    private final FieldCryptoProvider cryptoProvider;
    private final FieldSigner signer;

    public AiModelConfigurationService(BaseDao<AiModelConfiguration, String> dao,
                                       AiModelProviderService providerService,
                                       ObjectProvider<FieldCryptoProvider> cryptoProvider,
                                       ObjectProvider<FieldSigner> signer) {
        super(MODULE_ALIAS, AiModelConfiguration.class, dao);
        this.providerService = providerService;
        this.cryptoProvider = cryptoProvider.getIfAvailable(() -> FieldCryptoProvider.UNAVAILABLE);
        this.signer = signer.getIfAvailable(() -> FieldSigner.UNAVAILABLE);
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
                List.of("id", "tenantId", "title", "provider", "configurationLevel", "tenantFallbackEnabled",
                        "modelId", "apiKeyConfigured", "enabled", "createdAt", "updatedAt"));
    }

    @Override
    public void beforeInsert(AiModelConfiguration configuration) {
        normalize(configuration, null);
        requireNoConfigurationForScope(configuration);
        applyNewApiKey(configuration, null);
    }

    @Override
    public void beforeUpdate(AiModelConfiguration configuration, AiModelConfiguration existing) {
        if (existing == null) {
            throw new PlatformException("AI model configuration does not exist");
        }
        normalize(configuration, existing);
        if (!Objects.equals(configuration.getTenantId(), existing.getTenantId())) {
            requireNoConfigurationForScope(configuration);
        }
        applyNewApiKey(configuration, existing);
    }

    /**
     * Ownership is selected explicitly by a system administrator for this platform configuration.
     * Tenant callers remain confined to their current tenant by {@link #normalize}.
     */
    @Override
    public boolean allowsTenantOwnershipChange(AiModelConfiguration existing, AiModelConfiguration incoming) {
        return TenantContext.isSystem();
    }

    /** Resolves the enabled configuration for an explicit tenant or system execution context. */
    public AiModelConfiguration requireEffectiveConfiguration() {
        if (!TenantContext.hasContext()) {
            throw new PlatformConfigurationException(
                    "AI model routing requires an explicit tenant or system context");
        }
        String tenantId = TenantContext.currentTenantId().orElse(null);
        if (tenantId != null) {
            AiModelConfiguration tenant = enabledConfiguration(Criteria.of());
            if (tenant != null) return requireUsable(tenant, "tenant");
            return requireUsable(firstTenantFallbackConfiguration(), "tenant fallback");
        }
        return requireUsable(firstPlatformConfiguration(), "platform");
    }

    private void normalize(AiModelConfiguration configuration, AiModelConfiguration existing) {
        if (configuration.getProvider() == null || configuration.getProvider().isBlank()) {
            throw new PlatformException("AI model provider must not be blank");
        }
        configuration.setProvider(configuration.getProvider().trim());
        AiModelProvider provider = providerService.requireEnabled(configuration.getProvider());
        configuration.setProvider(provider.getId());
        if (configuration.getModelId() == null || configuration.getModelId().isBlank()) {
            throw new PlatformException("AI model id must not be blank");
        }
        configuration.setModelId(configuration.getModelId().trim());
        if (configuration.getTitle() == null || configuration.getTitle().isBlank()) {
            configuration.setTitle(provider.getTitle() + " · " + configuration.getModelId());
        } else {
            configuration.setTitle(configuration.getTitle().trim());
        }
        String currentTenantId = TenantContext.currentTenantId().orElse(null);
        if (currentTenantId != null) {
            configuration.setTenantId(currentTenantId);
        } else {
            String requestedTenantId = configuration.getTenantId();
            configuration.setTenantId(requestedTenantId == null || requestedTenantId.isBlank()
                    ? null : requestedTenantId.trim());
        }
        if (TenantContext.isSystem() && configuration.getTenantId() != null) {
            requireExistingTenant(configuration.getTenantId());
        }
        boolean platformLevel = configuration.getTenantId() == null;
        configuration.setConfigurationLevel(platformLevel
                ? AiModelConfigurationLevel.PLATFORM : AiModelConfigurationLevel.TENANT);
        if (!platformLevel || configuration.getTenantFallbackEnabled() == null) {
            configuration.setTenantFallbackEnabled(Boolean.FALSE);
        }
        configuration.setOwnershipScopeKey(configuration.getTenantId() == null ? "P" : "T:" + configuration.getTenantId());
        if (configuration.getEnabled() == null) {
            configuration.setEnabled(Boolean.TRUE);
        }
    }

    /**
     * A system-scoped configuration may target any tenant, so its ownership field must not rely
     * on the browser picker for referential integrity.  Resolve through the platform reference
     * facade rather than depending on the IAM service directly.
     */
    private void requireExistingTenant(String tenantId) {
        ReferenceAbility<?> tenants = PlatformAbilityRuntime.referenceTargetResolver().resolve(TENANT_TARGET)
                .orElseThrow(() -> new PlatformConfigurationException("tenant reference target is unavailable"));
        if (!tenants.titles(List.of(tenantId)).containsKey(tenantId)) {
            throw new PlatformException("AI model configuration tenant does not exist: " + tenantId);
        }
    }

    private void applyNewApiKey(AiModelConfiguration configuration, AiModelConfiguration existing) {
        String input = configuration.getApiKeyInput();
        configuration.setApiKeyInput(null);
        if (input == null || input.isBlank()) {
            if (existing == null || existing.getApiKey() == null || existing.getApiKey().isBlank()) {
                throw new PlatformException("AI model API key must not be blank");
            }
            retainProtectedFieldFromStorage(configuration, existing, "apiKey");
        } else {
            configuration.setApiKey(input.trim());
        }
        configuration.setApiKeyConfigured(Boolean.TRUE);
    }

    private AiModelConfiguration enabledConfiguration(Criteria criteria) {
        return findOne(enabledCriteria(criteria));
    }

    private AiModelConfiguration firstPlatformConfiguration() {
        try (TenantContext.Scope ignored = TenantContext.system("resolve platform AI model configuration")) {
            return enabledConfiguration(Criteria.of().isNull("tenantId"));
        }
    }

    private AiModelConfiguration firstTenantFallbackConfiguration() {
        try (TenantContext.Scope ignored = TenantContext.system("resolve tenant fallback AI model configuration")) {
            return enabledConfiguration(Criteria.of()
                    .isNull("tenantId")
                    .eq("tenantFallbackEnabled", Boolean.TRUE));
        }
    }

    private AiModelConfiguration requireUsable(AiModelConfiguration configuration, String scope) {
        if (configuration == null) {
            throw new PlatformConfigurationException("no usable " + scope + " AI model configuration exists");
        }
        if (configuration.getApiKey() == null || configuration.getApiKey().isBlank()) {
            throw new PlatformConfigurationException(scope + " AI model configuration has no API key");
        }
        return configuration;
    }

    private void requireNoConfigurationForScope(AiModelConfiguration configuration) {
        if (count(Criteria.of().eq("ownershipScopeKey", configuration.getOwnershipScopeKey())) > 0) {
            throw new PlatformException(configuration.getTenantId() == null
                    ? "a platform AI model configuration already exists"
                    : "an AI model configuration already exists for tenant: " + configuration.getTenantId());
        }
    }
}
