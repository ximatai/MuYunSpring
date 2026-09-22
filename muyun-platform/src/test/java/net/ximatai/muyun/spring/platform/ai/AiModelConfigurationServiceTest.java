package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.security.AesGcmFieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.ability.security.HmacSha256FieldSigner;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

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
    void clearContexts() {
        CurrentUserContext.clear();
        TenantContext.clear();
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void tenantConfigurationWinsOverPlatformFallback() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfiguration tenant = configuration("tenant", "tenant-key");
        AiModelConfiguration platform = configuration("platform", "platform-key");
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation ->
                TenantContext.isSystem() ? List.of(platform) : List.of(tenant));
        AiModelConfigurationService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.requireEffectiveConfiguration().getId()).isEqualTo("tenant");
        }
    }

    @Test
    void tenantFallbackConfigurationIsUsedWhenTenantHasNone() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfiguration platform = configuration("platform", "platform-key");
        platform.setTenantFallbackEnabled(Boolean.TRUE);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation ->
                TenantContext.isSystem() && Boolean.TRUE.equals(platform.getTenantFallbackEnabled())
                        ? List.of(platform) : List.of());
        AiModelConfigurationService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.requireEffectiveConfiguration().getId()).isEqualTo("platform");
        }
    }

    @Test
    void explicitSystemContextUsesThePlatformConfigurationWithoutRequiringTenantSharing() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfiguration platform = configuration("platform", "platform-key");
        platform.setTenantFallbackEnabled(Boolean.FALSE);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of(platform));
        AiModelConfigurationService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.system("platform AI invocation")) {
            assertThat(service.requireEffectiveConfiguration().getId()).isEqualTo("platform");
        }
    }

    @Test
    void platformAdministratorUsesPlatformConfigurationWhileManagingTenantData() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfiguration platform = configuration("platform", "platform-key");
        platform.setTenantFallbackEnabled(Boolean.FALSE);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation ->
                TenantContext.isSystem() ? List.of(platform) : List.of());
        AiModelConfigurationService service = service(dao);

        try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                CurrentUser.systemUser("platform-admin", "admin"));
             TenantContext.Scope ignoredTenant = TenantContext.use("tenant-a")) {
            assertThat(service.requireEffectiveConfiguration().getId()).isEqualTo("platform");
        }
    }

    @Test
    void platformOnlyConfigurationIsNotAvailableAsTenantFallback() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfiguration platformOnly = configuration("platform", "platform-key");
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of());
        AiModelConfigurationService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(service::requireEffectiveConfiguration)
                    .isInstanceOf(PlatformException.class)
                    .hasMessage("no usable tenant fallback AI model configuration exists");
        }

        assertThat(platformOnly.getTenantFallbackEnabled()).isFalse();
    }

    @Test
    void missingExecutionContextCannotFallThroughToThePlatformConfiguration() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfigurationService service = service(dao);

        assertThatThrownBy(service::requireEffectiveConfiguration)
                .isInstanceOf(PlatformException.class)
                .hasMessage("AI model routing requires an explicit tenant or system context");
    }

    @Test
    void platformAdministratorStillRequiresAnExplicitExecutionContext() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfigurationService service = service(dao);

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.systemUser("platform-admin", "admin"))) {
            assertThatThrownBy(service::requireEffectiveConfiguration)
                    .isInstanceOf(PlatformException.class)
                    .hasMessage("AI model routing requires an explicit tenant or system context");
        }
    }

    @Test
    void systemAdministratorCanCreateOneConfigurationForAnExplicitTenant() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        when(dao.count(any())).thenReturn(0L);
        tenantExists("tenant-a");
        AiModelConfigurationService service = service(dao);
        AiModelConfiguration configuration = input("tenant-model", "tenant-secret");
        configuration.setTenantId(" tenant-a ");

        try (TenantContext.Scope ignored = TenantContext.system("admin creates tenant configuration")) {
            service.beforeInsert(configuration);
        }

        assertThat(configuration.getTenantId()).isEqualTo("tenant-a");
        assertThat(configuration.getConfigurationLevel()).isEqualTo(AiModelConfigurationLevel.TENANT);
        assertThat(configuration.getTenantFallbackEnabled()).isFalse();
        assertThat(configuration.getOwnershipScopeKey()).isEqualTo("T:tenant-a");
    }

    @Test
    void systemAdministratorCannotCreateConfigurationForAnUnknownTenant() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        tenantExists();
        AiModelConfigurationService service = service(dao);
        AiModelConfiguration configuration = input("tenant-model", "tenant-secret");
        configuration.setTenantId("missing-tenant");

        try (TenantContext.Scope ignored = TenantContext.system("admin creates tenant configuration")) {
            assertThatThrownBy(() -> service.beforeInsert(configuration))
                    .isInstanceOf(PlatformException.class)
                    .hasMessage("AI model configuration tenant does not exist: missing-tenant");
        }
    }

    @Test
    void tenantCallerCannotCreateOrRetargetAnotherTenantsConfiguration() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        when(dao.count(any())).thenReturn(0L);
        AiModelConfigurationService service = service(dao);
        AiModelConfiguration configuration = input("tenant-model", "tenant-secret");
        configuration.setTenantId("other-tenant");
        configuration.setTenantFallbackEnabled(Boolean.TRUE);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.beforeInsert(configuration);
        }

        assertThat(configuration.getTenantId()).isEqualTo("tenant-a");
        assertThat(configuration.getConfigurationLevel()).isEqualTo(AiModelConfigurationLevel.TENANT);
        assertThat(configuration.getTenantFallbackEnabled()).isFalse();
        assertThat(configuration.getOwnershipScopeKey()).isEqualTo("T:tenant-a");

        AiModelConfiguration existing = configuration("existing", "tenant-secret");
        existing.setTenantId("tenant-a");
        AiModelConfiguration update = input("tenant-model", null);
        update.setTenantId("other-tenant");
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.beforeUpdate(update, existing);
        }
        assertThat(update.getTenantId()).isEqualTo("tenant-a");
    }

    @Test
    void systemAdministratorCanRetargetTheSharedConfiguration() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        when(dao.count(any())).thenReturn(0L);
        tenantExists("tenant-b");
        AiModelConfigurationService service = service(dao);
        AiModelConfiguration existing = configuration("existing", "tenant-secret");
        existing.setTenantId("tenant-a");
        AiModelConfiguration incoming = input("tenant-model", null);
        incoming.setTenantId(" tenant-b ");

        try (TenantContext.Scope ignored = TenantContext.system("admin updates tenant configuration")) {
            assertThat(service.allowsTenantOwnershipChange(existing, incoming)).isTrue();
            service.beforeUpdate(incoming, existing);
        }

        assertThat(incoming.getTenantId()).isEqualTo("tenant-b");
        assertThat(incoming.getConfigurationLevel()).isEqualTo(AiModelConfigurationLevel.TENANT);
        assertThat(incoming.getTenantFallbackEnabled()).isFalse();
        assertThat(incoming.getOwnershipScopeKey()).isEqualTo("T:tenant-b");
        assertThat(incoming.getApiKey()).isEqualTo("tenant-secret");
        assertThat(existing.getApiKey()).startsWith("v1:");
    }

    @Test
    void tenantCallerCannotOptIntoChangingConfigurationOwnership() {
        AiModelConfigurationService service = service(mock(BaseDao.class));
        AiModelConfiguration existing = configuration("existing", "tenant-secret");
        existing.setTenantId("tenant-a");
        AiModelConfiguration incoming = input("tenant-model", null);
        incoming.setTenantId("tenant-b");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.allowsTenantOwnershipChange(existing, incoming)).isFalse();
        }
    }

    @Test
    void systemAdministratorCannotRetargetConfigurationToAnOccupiedScope() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        when(dao.count(any())).thenReturn(1L);
        tenantExists("tenant-b");
        AiModelConfigurationService service = service(dao);
        AiModelConfiguration existing = configuration("existing", "tenant-secret");
        existing.setTenantId("tenant-a");
        AiModelConfiguration incoming = input("tenant-model", null);
        incoming.setTenantId("tenant-b");

        try (TenantContext.Scope ignored = TenantContext.system("admin updates tenant configuration")) {
            assertThatThrownBy(() -> service.beforeUpdate(incoming, existing))
                    .isInstanceOf(PlatformException.class)
                    .hasMessage("an AI model configuration already exists for tenant: tenant-b");
        }
    }

    @Test
    void rejectsAnotherConfigurationInTheSameOwnershipScope() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        when(dao.count(any())).thenReturn(1L);
        tenantExists("tenant-a");
        AiModelConfigurationService service = service(dao);

        assertThatThrownBy(() -> service.beforeInsert(input("global-model", "global-secret")))
                .isInstanceOf(PlatformException.class)
                .hasMessage("a platform AI model configuration already exists");

        AiModelConfiguration tenantInput = input("tenant-model", "tenant-secret");
        tenantInput.setTenantId("tenant-a");
        try (TenantContext.Scope ignored = TenantContext.system("admin creates duplicate tenant configuration")) {
            assertThatThrownBy(() -> service.beforeInsert(tenantInput))
                    .isInstanceOf(PlatformException.class)
                    .hasMessage("an AI model configuration already exists for tenant: tenant-a");
        }
    }

    @Test
    void fieldProtectionEncryptsSignsAndReportsConfiguredApiKey() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of());
        AiModelConfigurationService service = service(dao);
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setProvider(AiModelProviderService.LM_STUDIO_ID);
        configuration.setModelId("local-model");
        configuration.setApiKeyInput("model-secret");
        configuration.setConfigurationLevel(AiModelConfigurationLevel.TENANT);
        configuration.setTenantFallbackEnabled(Boolean.TRUE);

        service.beforeInsert(configuration);
        assertThat(configuration.getTitle()).isEqualTo("LM Studio · local-model");
        assertThat(configuration.getConfigurationLevel()).isEqualTo(AiModelConfigurationLevel.PLATFORM);
        assertThat(configuration.getTenantFallbackEnabled()).isTrue();
        assertThat(configuration.getOwnershipScopeKey()).isEqualTo("P");
        try (var mutation = service.protectFieldsForStorage(configuration)) {
            assertThat(configuration.getApiKey()).startsWith("v1:").isNotEqualTo("model-secret");
            assertThat(configuration.getApiKeySignature()).isNotBlank();
        }

        assertThat(configuration.getApiKey()).isEqualTo("model-secret");
        assertThat(configuration.getApiKeyInput()).isNull();
        assertThat(configuration.getApiKeyConfigured()).isTrue();
    }

    @Test
    void tenantCannotSaveEnvironmentReferencesEvenWhenTenantFilteringIsBypassed() {
        var service = service(mock(BaseDao.class));
        var configuration = input("model", null);
        configuration.setCredentialSource(AiModelCredentialSource.ENVIRONMENT);
        configuration.setApiKeyEnvironmentVariable("MUYUN_AI_TEST_KEY");
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("u", "u", "tenant"));
             var tenant = TenantContext.use("tenant");
             var bypass = TenantContext.bypassTenantFilter("contract")) {
            assertThatThrownBy(() -> service.beforeInsert(configuration)).hasMessageContaining("只有平台管理员");
        }
    }

    private AiModelConfigurationService service(BaseDao<AiModelConfiguration, String> dao) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("crypto", crypto);
        beans.registerSingleton("signer", signer);
        AiModelProviderService providers = mock(AiModelProviderService.class);
        AiModelProvider provider = new AiModelProvider();
        provider.setId(AiModelProviderService.LM_STUDIO_ID);
        provider.setTitle("LM Studio");
        provider.setEnabled(Boolean.TRUE);
        provider.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        provider.setBaseUrl("http://127.0.0.1:1234/v1");
        when(providers.requireEnabled(AiModelProviderService.LM_STUDIO_ID)).thenReturn(provider);
        return new AiModelConfigurationService(dao, providers,
                beans.getBeanProvider(FieldCryptoProvider.class), beans.getBeanProvider(FieldSigner.class));
    }

    @SuppressWarnings("unchecked")
    private void tenantExists(String... tenantIds) {
        ReferenceAbility<?> tenants = mock(ReferenceAbility.class);
        List<String> existing = List.of(tenantIds);
        when(tenants.titles(any())).thenAnswer(invocation -> {
            List<String> requested = invocation.getArgument(0);
            return requested.stream().filter(existing::contains)
                    .collect(java.util.stream.Collectors.toMap(id -> id, id -> id));
        });
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> Optional.of(tenants));
    }

    private AiModelConfiguration configuration(String id, String key) {
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setId(id);
        configuration.setProvider(AiModelProviderService.LM_STUDIO_ID);
        configuration.setModelId("local-model");
        configuration.setConfigurationLevel(AiModelConfigurationLevel.PLATFORM);
        configuration.setEnabled(Boolean.TRUE);
        configuration.setApiKey(crypto.encrypt("apiKey", key));
        configuration.setApiKeySignature(signer.sign("apiKey", key));
        return configuration;
    }

    private AiModelConfiguration input(String modelId, String apiKey) {
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setProvider(AiModelProviderService.LM_STUDIO_ID);
        configuration.setModelId(modelId);
        configuration.setApiKeyInput(apiKey);
        return configuration;
    }
}
