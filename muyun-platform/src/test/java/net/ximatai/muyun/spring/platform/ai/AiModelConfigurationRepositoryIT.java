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
    @Autowired DataSource dataSource;

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
            // Reproduce a retained column from the retired availability-scope model.
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.execute("ALTER TABLE platform_ai_model_configuration ADD COLUMN availability_scope varchar(32) NOT NULL");
            AiModelConfiguration rejected = environmentInput();
            assertThatThrownBy(() -> configurations.insert(rejected))
                    .hasStackTraceContaining("availability_scope");
            // The documented non-destructive upgrade retains old values but removes the obsolete constraint.
            jdbc.execute("ALTER TABLE platform_ai_model_configuration ALTER COLUMN availability_scope DROP NOT NULL");
            AiModelConfiguration platform = environmentInput();
            platform.setTenantFallbackEnabled(Boolean.TRUE);
            String platformId = configurations.insert(platform);
            assertThat(dao.findById(platformId).getApiKey()).isNull();
            assertThat(dao.findById(platformId).getApiKeySignature()).isNull();
            var credential = new java.util.concurrent.atomic.AtomicReference<>("environment-key");
            var resolver = new DefaultAiModelRouteResolver(configurations, providers,
                    new AiModelCredentialResolver(name -> credential.get()));
            var client = org.mockito.Mockito.mock(AiModelClient.class);
            var gateway = new DefaultAiModelGateway(resolver, client);
            gateway.generate(AiTextRequest.userText("contract"));
            credential.set("rotated-environment-key");
            gateway.stream(AiTextRequest.userText("contract"), delta -> {});
            var captured = org.mockito.ArgumentCaptor.forClass(ResolvedAiModelRoute.class);
            org.mockito.Mockito.verify(client).generate(captured.capture(), org.mockito.ArgumentMatchers.any(AiTextRequest.class));
            assertThat(captured.getValue().apiKey()).isEqualTo("environment-key");
            org.mockito.Mockito.verify(client).stream(captured.capture(), org.mockito.ArgumentMatchers.any(AiTextRequest.class),
                    org.mockito.ArgumentMatchers.any(AiTextStreamConsumer.class));
            assertThat(captured.getValue().apiKey()).isEqualTo("rotated-environment-key");
            try (var tenantUser = CurrentUserContext.use(CurrentUser.tenantUser("u", "u", "env-fallback"));
                 var tenant = TenantContext.use("env-fallback")) {
                assertThat(resolver.resolveCurrent().apiKey()).isEqualTo("rotated-environment-key");
            }
            assertThat(configurations.select(platformId).getApiKey()).isNull();
            AiModelConfiguration initialDirect = configurations.select(platformId);
            initialDirect.setCredentialSource(AiModelCredentialSource.DIRECT);
            initialDirect.setApiKeyInput("platform-key");
            configurations.update(initialDirect);
            exerciseCredentialUpdates(platformId);
            AiModelConfiguration environment = configurations.select(platformId);
            environment.setCredentialSource(AiModelCredentialSource.ENVIRONMENT);
            environment.setApiKeyEnvironmentVariable("MUYUN_AI_CONTRACT_KEY");
            configurations.update(environment);
            assertThat(dao.findById(platformId).getApiKey()).isNull();
            assertThat(dao.findById(platformId).getApiKeySignature()).isNull();
            assertThat(configurations.select(platformId).getApiKeyEnvironmentVariable())
                    .isEqualTo("MUYUN_AI_CONTRACT_KEY");
            configurations.disable(platformId);
            configurations.enable(platformId);
            AiModelConfiguration direct = configurations.select(platformId);
            direct.setCredentialSource(AiModelCredentialSource.DIRECT);
            assertThatThrownBy(() -> configurations.update(direct)).hasMessage("AI model API key must not be blank");
            direct.setVersion(configurations.select(platformId).getVersion());
            direct.setApiKeyInput("restored-key");
            configurations.update(direct);
            assertCredential(platformId, "restored-key");
            assertThat(dao.findById(platformId).getApiKeyEnvironmentVariable()).isNull();

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

    private static AiModelConfiguration environmentInput() {
        AiModelConfiguration record = input(null);
        record.setCredentialSource(AiModelCredentialSource.ENVIRONMENT);
        record.setApiKeyEnvironmentVariable("SHARED_MODEL_KEY");
        record.setTenantFallbackEnabled(Boolean.TRUE);
        return record;
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
