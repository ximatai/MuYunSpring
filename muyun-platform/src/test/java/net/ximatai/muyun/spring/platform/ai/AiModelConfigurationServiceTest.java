package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.security.AesGcmFieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.ability.security.HmacSha256FieldSigner;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelConfigurationServiceTest {
    private final FieldCryptoProvider crypto = new AesGcmFieldCryptoProvider(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private final FieldSigner signer = new HmacSha256FieldSigner(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
    }

    @Test
    void tenantOwnedConfigurationWinsOverTargetedAndGlobalConfiguration() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfiguration tenant = configuration("tenant", "tenant-key", 20);
        AiModelConfiguration targeted = configuration("targeted", "targeted-key", 10);
        AiModelConfiguration global = configuration("global", "global-key", 10);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation ->
                TenantContext.isSystem() ? List.of(targeted, global) : List.of(tenant));
        AiModelConfigurationTenantService grants = mock(AiModelConfigurationTenantService.class);
        when(grants.configurationIdsForTenant("tenant-a")).thenReturn(List.of("targeted"));
        AiModelConfigurationService service = service(dao, grants);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.requireEffectiveConfiguration().getId()).isEqualTo("tenant");
        }
    }

    @Test
    void targetedPlatformConfigurationWinsOverGlobalConfiguration() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfiguration targeted = configuration("targeted", "targeted-key", 10);
        AiModelConfiguration global = configuration("global", "global-key", 20);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation ->
                TenantContext.isSystem() ? List.of(targeted, global) : List.of());
        AiModelConfigurationTenantService grants = mock(AiModelConfigurationTenantService.class);
        when(grants.configurationIdsForTenant("tenant-a")).thenReturn(List.of("targeted"));
        AiModelConfigurationService service = service(dao, grants);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.requireEffectiveConfiguration().getId()).isEqualTo("targeted");
        }
    }

    @Test
    void fieldProtectionEncryptsSignsAndReportsConfiguredApiKey() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of());
        AiModelConfigurationService service = service(dao, null);
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setProvider(AiModelProviderService.LM_STUDIO_ID);
        configuration.setModelId("local-model");
        configuration.setApiKeyInput("model-secret");

        service.beforeInsert(configuration);
        assertThat(configuration.getTitle()).isEqualTo("LM Studio · local-model");
        try (var mutation = service.protectFieldsForStorage(configuration)) {
            assertThat(configuration.getApiKey()).startsWith("v1:").isNotEqualTo("model-secret");
            assertThat(configuration.getApiKeySignature()).isNotBlank();
        }
        service.afterSelect(configuration);

        assertThat(configuration.getApiKey()).isEqualTo("model-secret");
        assertThat(configuration.getApiKeyInput()).isNull();
        assertThat(configuration.getApiKeyConfigured()).isTrue();
    }

    @Test
    void platformSortScopeUsesIsNullForTheAbsentTenant() {
        AiModelConfigurationService service = service(mock(BaseDao.class), null);
        AiModelConfiguration configuration = configuration("platform", "model-key", 10);
        configuration.setTenantId(null);

        assertThat(service.sortScope(configuration).getClauses())
                .anySatisfy(clause -> {
                    assertThat(clause.getField()).isEqualTo("tenantId");
                    assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.IS_NULL);
                });
    }

    private AiModelConfigurationService service(BaseDao<AiModelConfiguration, String> dao,
                                                AiModelConfigurationTenantService grants) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("crypto", crypto);
        beans.registerSingleton("signer", signer);
        if (grants != null) beans.registerSingleton("grants", grants);
        AiModelProviderService providers = mock(AiModelProviderService.class);
        AiModelProvider provider = new AiModelProvider();
        provider.setId(AiModelProviderService.LM_STUDIO_ID);
        provider.setTitle("LM Studio");
        provider.setEnabled(Boolean.TRUE);
        provider.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        provider.setBaseUrl("http://127.0.0.1:1234/v1");
        when(providers.requireEnabled(AiModelProviderService.LM_STUDIO_ID)).thenReturn(provider);
        return new AiModelConfigurationService(dao, providers,
                beans.getBeanProvider(AiModelConfigurationTenantService.class),
                beans.getBeanProvider(FieldCryptoProvider.class), beans.getBeanProvider(FieldSigner.class));
    }

    private AiModelConfiguration configuration(String id, String key, int sortOrder) {
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setId(id);
        configuration.setProvider(AiModelProviderService.LM_STUDIO_ID);
        configuration.setModelId("local-model");
        configuration.setAvailabilityScope(AiModelAvailabilityScope.PLATFORM);
        configuration.setEnabled(Boolean.TRUE);
        configuration.setSortOrder(sortOrder);
        configuration.setApiKey(crypto.encrypt("apiKey", key));
        configuration.setApiKeySignature(signer.sign("apiKey", key));
        return configuration;
    }
}
