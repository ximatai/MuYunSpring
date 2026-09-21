package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.security.AesGcmFieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.ability.security.HmacSha256FieldSigner;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = AiModelConfigurationRepositoryIT.TestApplication.class)
class AiModelConfigurationRepositoryIT extends PlatformPostgresIntegrationTest {
    @Autowired AiModelConfigurationService configurations;
    @Autowired AiModelProviderService providers;
    @Autowired AiModelConfigurationDao dao;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Test
    void shouldPreserveCredentialsThroughRealUpdatesEnableChangesAndTenantFallback() {
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("root", "root"));
             var scope = TenantContext.system("AI credential contract")) {
            AiModelProvider provider = providers.initialData().getLast();
            providers.insert(provider);
            provider.setTitle("Local contract provider");
            providers.update(provider);
            assertThat(providers.requireEnabled(provider.getId()).getTitle()).isEqualTo("Local contract provider");
            AiModelConfiguration platform = input("platform-key");
            platform.setTenantFallbackEnabled(Boolean.TRUE);
            String platformId = configurations.insert(platform);
            exerciseCredentialUpdates(platformId);

            AiModelConfiguration platformOnly = configurations.select(platformId);
            platformOnly.setTenantFallbackEnabled(Boolean.FALSE);
            configurations.update(platformOnly);
            try (var tenantUser = CurrentUserContext.use(CurrentUser.tenantUser(
                    "tenant-user", "Tenant User", "tenant-without-fallback"));
                 var tenant = TenantContext.use("tenant-without-fallback")) {
                assertThatThrownBy(configurations::requireEffectiveConfiguration)
                        .isInstanceOf(PlatformException.class)
                        .hasMessage("no usable tenant fallback AI model configuration exists");
            }
            AiModelConfiguration fallback = configurations.select(platformId);
            fallback.setTenantFallbackEnabled(Boolean.TRUE);
            configurations.update(fallback);

            try (var tenantUser = CurrentUserContext.use(CurrentUser.tenantUser(
                    "tenant-user", "Tenant User", "tenant-ai-contract"));
                 var tenant = TenantContext.use("tenant-ai-contract")) {
                assertThat(configurations.requireEffectiveConfiguration().getId()).isEqualTo(platformId);
                String tenantId = configurations.insert(input("tenant-key"));
                assertThat(configurations.requireEffectiveConfiguration().getId()).isEqualTo(tenantId);
                try (var bypass = TenantContext.bypassTenantFilter("cross-tenant action contract")) {
                    assertThat(configurations.requireEffectiveConfiguration().getId()).isEqualTo(tenantId);
                }
                exerciseCredentialUpdates(tenantId);
                configurations.disable(tenantId);
                AiModelConfiguration disabled = dao.findById(tenantId);
                disabled.setApiKey("invalid-old-ciphertext");
                dao.updateById(disabled);
                assertThat(configurations.requireEffectiveConfiguration().getId()).isEqualTo(platformId);
            }
        }
    }

    private void exerciseCredentialUpdates(String id) {
        String originalKey = configurations.select(id).getApiKey();
        AiModelConfiguration update = input(null);
        update.setId(id);
        update.setVersion(configurations.select(id).getVersion());
        update.setTitle("Renamed configuration");
        update.setTenantFallbackEnabled(configurations.select(id).getTenantFallbackEnabled());
        configurations.update(update);
        assertCredential(id, originalKey);
        configurations.disable(id);
        assertCredential(id, originalKey);
        configurations.enable(id);
        assertCredential(id, originalKey);
        update.setVersion(configurations.select(id).getVersion());
        update.setApiKeyInput("replacement-key");
        configurations.update(update);
        assertCredential(id, "replacement-key");
    }

    private void assertCredential(String id, String expected) {
        AiModelConfiguration stored = dao.findById(id);
        assertThat(stored.getApiKey()).startsWith("v1:").doesNotContain(expected);
        assertThat(stored.getApiKeySignature()).isNotBlank();
        assertThat(configurations.select(id).getApiKey()).isEqualTo(expected);
        assertThat(configurations.select(id).getApiKey()).isEqualTo(expected); // cache hit
    }

    private static AiModelConfiguration input(String key) {
        AiModelConfiguration record = new AiModelConfiguration();
        record.setProvider(AiModelProviderService.LM_STUDIO_ID);
        record.setModelId("contract-model");
        record.setApiKeyInput(key);
        return record;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = AiModelConfigurationDao.class)
    @Import({AiModelConfigurationService.class, AiModelProviderService.class})
    static class TestApplication {
        private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean FieldCryptoProvider fieldCryptoProvider() { return new AesGcmFieldCryptoProvider(KEY); }
        @Bean FieldSigner fieldSigner() { return new HmacSha256FieldSigner(KEY); }
    }
}
