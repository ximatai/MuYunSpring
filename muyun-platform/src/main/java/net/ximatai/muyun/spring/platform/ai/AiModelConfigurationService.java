package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/** Resolves the first enabled model in the tenant, targeted-platform, then global-platform priority order. */
@Service
public class AiModelConfigurationService extends AbstractAbilityService<AiModelConfiguration> implements
        EnableAbility<AiModelConfiguration>,
        SortAbility<AiModelConfiguration>,
        CacheAbility<AiModelConfiguration>,
        QueryAbility<AiModelConfiguration>,
        FieldProtectionAbility<AiModelConfiguration>,
        ReferenceAbility<AiModelConfiguration> {
    public static final String MODULE_ALIAS = "platform.ai_model_configuration";

    private final AiModelProviderService providerService;
    private final FieldCryptoProvider cryptoProvider;
    private final FieldSigner signer;

    public AiModelConfigurationService(BaseDao<AiModelConfiguration, String> dao,
                                       AiModelProviderService providerService,
                                       ObjectProvider<FieldCryptoProvider> cryptoProvider,
                                       ObjectProvider<FieldSigner> signer) {
        super(MODULE_ALIAS, AiModelConfiguration.class, dao);
        this.providerService = providerService;
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
                List.of("id", "tenantId", "title", "provider", "availabilityScope", "modelId", "apiKeyConfigured",
                        "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    /**
     * Platform-owned configurations have a {@code null} tenant id.  The generic field partition
     * helper expresses that as {@code = null}, which cannot be compiled by the database criteria
     * engine; it must be an explicit {@code IS NULL} predicate instead.
     */
    @Override
    public Criteria sortScope(AiModelConfiguration configuration) {
        Criteria criteria = Criteria.of();
        if (configuration.getTenantId() == null || configuration.getTenantId().isBlank()) {
            criteria.isNull("tenantId");
        } else {
            criteria.eq("tenantId", configuration.getTenantId());
        }
        return criteria.eq("availabilityScope", configuration.getAvailabilityScope());
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
        configuration.setTenantId(existing.getTenantId());
        normalize(configuration, existing);
        applyNewApiKey(configuration, existing);
    }

    @Override
    public void afterSelect(AiModelConfiguration configuration) {
        boolean configured = configuration.getApiKey() != null && !configuration.getApiKey().isBlank();
        configuration.setApiKeyConfigured(configured);
    }

    /** Resolves the first enabled candidate within each ownership/availability tier. */
    public AiModelConfiguration requireEffectiveConfiguration() {
        String tenantId = TenantContext.currentTenantId().orElse(null);
        if (tenantId != null) {
            AiModelConfiguration tenant = firstEnabled(Criteria.of());
            if (tenant != null) return requireUsable(tenant, "tenant");
        }
        return requireUsable(firstPlatformConfiguration(), "platform");
    }

    public AiModelConfiguration requireCurrentScopeConfiguration() {
        AiModelConfiguration configuration = firstEnabled(Criteria.of());
        if (configuration == null) {
            throw new PlatformConfigurationException("AI model configuration is missing for current scope");
        }
        return configuration;
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
        } else if (existing == null) {
            String targetTenantId = configuration.getTenantId();
            configuration.setTenantId(targetTenantId == null || targetTenantId.isBlank() ? null : targetTenantId.trim());
        } else {
            configuration.setTenantId(existing.getTenantId());
        }
        configuration.setAvailabilityScope(configuration.getTenantId() == null
                ? AiModelAvailabilityScope.PLATFORM : AiModelAvailabilityScope.TENANT_PRIVATE);
        configuration.setOwnershipScopeKey(configuration.getTenantId() == null ? "G" : "T:" + configuration.getTenantId());
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
            configuration.setApiKeyConfigured(Boolean.TRUE);
            return;
        }
        configuration.setApiKey(input.trim());
        configuration.setApiKeyConfigured(Boolean.TRUE);
    }

    private AiModelConfiguration firstEnabled(Criteria criteria) {
        return sortedList(criteria).stream().filter(item -> Boolean.TRUE.equals(item.getEnabled())).findFirst().orElse(null);
    }

    private AiModelConfiguration firstPlatformConfiguration() {
        try (TenantContext.Scope ignored = TenantContext.system("resolve platform AI model configuration")) {
            return firstEnabled(Criteria.of().eq("availabilityScope", AiModelAvailabilityScope.PLATFORM));
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
        Criteria criteria = Criteria.of();
        if (configuration.getTenantId() == null) criteria.isNull("tenantId");
        else criteria.eq("tenantId", configuration.getTenantId());
        criteria.eq("availabilityScope", configuration.getAvailabilityScope());
        if (count(criteria) > 0) {
            throw new PlatformException(configuration.getTenantId() == null
                    ? "a global AI model configuration already exists"
                    : "an AI model configuration already exists for tenant: " + configuration.getTenantId());
        }
    }
}
