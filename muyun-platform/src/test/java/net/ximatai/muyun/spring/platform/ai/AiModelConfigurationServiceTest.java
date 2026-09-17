package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.security.AesGcmFieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.ability.security.HmacSha256FieldSigner;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelConfigurationServiceTest {
    private final FieldCryptoProvider crypto = new AesGcmFieldCryptoProvider(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private final FieldSigner signer = new HmacSha256FieldSigner(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tenantDisabledConfigurationDoesNotFallBackToPlatformConfiguration() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelPlatformSettingService settings = mock(AiModelPlatformSettingService.class);
        when(settings.tenantRegistrationEnabled()).thenReturn(true);
        AiModelConfiguration tenant = protectedConfiguration("tenant-key", false);
        AiModelConfiguration platform = protectedConfiguration("platform-key", true);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation ->
                TenantContext.isSystem() ? List.of(platform) : List.of(tenant));
        AiModelConfigurationService service = service(dao, settings);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(service::requireEffectiveConfiguration)
                    .isInstanceOf(PlatformConfigurationException.class)
                    .hasMessageContaining("tenant AI model configuration is disabled");
        }
    }

    @Test
    void fieldProtectionEncryptsAndSignsConfiguredApiKey() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelPlatformSettingService settings = mock(AiModelPlatformSettingService.class);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of());
        AiModelConfigurationService service = service(dao, settings);
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setProvider(AiModelProvider.LM_STUDIO);
        configuration.setModelId("local-model");
        configuration.setApiKeyInput("model-secret");

        service.beforeInsert(configuration);
        try (var mutation = service.protectFieldsForStorage(configuration)) {
            assertThat(configuration.getApiKey()).startsWith("v1:").isNotEqualTo("model-secret");
            assertThat(configuration.getApiKeySignature()).isNotBlank();
        }

        assertThat(configuration.getApiKey()).isEqualTo("model-secret");
        assertThat(configuration.getApiKeyInput()).isNull();
    }

    private AiModelConfigurationService service(BaseDao<AiModelConfiguration, String> dao,
                                                AiModelPlatformSettingService settings) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("crypto", crypto);
        beans.registerSingleton("signer", signer);
        return new AiModelConfigurationService(dao, settings,
                beans.getBeanProvider(FieldCryptoProvider.class), beans.getBeanProvider(FieldSigner.class));
    }

    private AiModelConfiguration protectedConfiguration(String key, boolean enabled) {
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setProvider(AiModelProvider.LM_STUDIO);
        configuration.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        configuration.setModelId("local-model");
        configuration.setEnabled(enabled);
        configuration.setApiKey(crypto.encrypt("apiKey", key));
        configuration.setApiKeySignature(signer.sign("apiKey", key));
        return configuration;
    }
}
