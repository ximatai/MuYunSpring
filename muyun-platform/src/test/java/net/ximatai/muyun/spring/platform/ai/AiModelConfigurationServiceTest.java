package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.security.AesGcmFieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.ability.security.HmacSha256FieldSigner;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.exception.PlatformException;
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

    @Test
    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void tenantConfigurationWinsOverGlobalConfiguration() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfiguration tenant = configuration("tenant", "tenant-key", 20);
        AiModelConfiguration global = configuration("global", "global-key", 10);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation ->
                TenantContext.isSystem() ? List.of(global) : List.of(tenant));
        AiModelConfigurationService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.requireEffectiveConfiguration().getId()).isEqualTo("tenant");
        }
    }

    @Test
    void globalConfigurationIsUsedWhenTenantHasNone() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        AiModelConfiguration global = configuration("global", "global-key", 20);
        when(dao.query(any(), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation ->
                TenantContext.isSystem() ? List.of(global) : List.of());
        AiModelConfigurationService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.requireEffectiveConfiguration().getId()).isEqualTo("global");
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
        assertThat(configuration.getAvailabilityScope()).isEqualTo(AiModelAvailabilityScope.TENANT_PRIVATE);
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

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.beforeInsert(configuration);
        }

        assertThat(configuration.getTenantId()).isEqualTo("tenant-a");
        assertThat(configuration.getAvailabilityScope()).isEqualTo(AiModelAvailabilityScope.TENANT_PRIVATE);
        assertThat(configuration.getOwnershipScopeKey()).isEqualTo("T:tenant-a");
    }

    @Test
    void updateKeepsTheOriginalTenantOwnershipForTheSharedRecord() {
        tenantExists("tenant-a");
        AiModelConfigurationService service = service(mock(BaseDao.class));
        AiModelConfiguration existing = configuration("existing", "tenant-secret", 100);
        existing.setTenantId("tenant-a");
        AiModelConfiguration incoming = input("tenant-model", null);
        incoming.setTenantId("other-tenant");

        try (TenantContext.Scope ignored = TenantContext.system("admin updates tenant configuration")) {
            service.beforeUpdate(incoming, existing);
        }

        assertThat(incoming.getTenantId()).isEqualTo("tenant-a");
        assertThat(incoming.getAvailabilityScope()).isEqualTo(AiModelAvailabilityScope.TENANT_PRIVATE);
        assertThat(incoming.getOwnershipScopeKey()).isEqualTo("T:tenant-a");
        assertThat(incoming.getApiKey()).isEqualTo(existing.getApiKey());
    }

    @Test
    void rejectsAnotherConfigurationInTheSameOwnershipScope() {
        BaseDao<AiModelConfiguration, String> dao = mock(BaseDao.class);
        when(dao.count(any())).thenReturn(1L);
        tenantExists("tenant-a");
        AiModelConfigurationService service = service(dao);

        assertThatThrownBy(() -> service.beforeInsert(input("global-model", "global-secret")))
                .isInstanceOf(PlatformException.class)
                .hasMessage("a global AI model configuration already exists");

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
    void persistedTenantOwnershipIsAvailableForReadSideProjection() {
        AiModelConfigurationService service = service(mock(BaseDao.class));
        AiModelConfiguration configuration = configuration("tenant", "tenant-key", 10);
        configuration.setTenantId("tenant-a");
        assertThat(configuration.getTenantId()).isEqualTo("tenant-a");
    }

    @Test
    void platformSortScopeUsesIsNullForTheAbsentTenant() {
        AiModelConfigurationService service = service(mock(BaseDao.class));
        AiModelConfiguration configuration = configuration("platform", "model-key", 10);
        configuration.setTenantId(null);

        assertThat(service.sortScope(configuration).getClauses())
                .anySatisfy(clause -> {
                    assertThat(clause.getField()).isEqualTo("tenantId");
                    assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.IS_NULL);
                });
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

    private AiModelConfiguration input(String modelId, String apiKey) {
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setProvider(AiModelProviderService.LM_STUDIO_ID);
        configuration.setModelId(modelId);
        configuration.setApiKeyInput(apiKey);
        return configuration;
    }
}
