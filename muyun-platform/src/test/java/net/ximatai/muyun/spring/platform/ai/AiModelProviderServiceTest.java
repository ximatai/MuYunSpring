package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiModelProviderServiceTest {
    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CurrentUserContext.clear();
    }

    @Test
    void permitsOnlyLoopbackHttpAndNormalizesTrailingSlash() {
        AiModelProviderService service = new AiModelProviderService(mock(BaseDao.class));
        AiModelProvider provider = provider("http://127.0.0.1:1234/v1/");
        try (TenantContext.Scope ignored = TenantContext.system("test");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(CurrentUser.systemUser("root", "root"))) {
            service.beforePrepareInsert(provider);
            service.beforeInsert(provider);
        }
        assertThat(provider.getBaseUrl()).isEqualTo("http://127.0.0.1:1234/v1");
    }

    @Test
    void rejectsRemoteHttpAndUrlsWithQueryOrCredentials() {
        AiModelProviderService service = new AiModelProviderService(mock(BaseDao.class));
        try (TenantContext.Scope ignored = TenantContext.system("test");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(CurrentUser.systemUser("root", "root"))) {
            assertThatThrownBy(() -> insert(service, "http://api.example.com/v1"))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("HTTPS");
            assertThatThrownBy(() -> insert(service, "https://user:password@example.com/v1"))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("HTTPS");
            assertThatThrownBy(() -> insert(service, "https://api.example.com/v1?key=secret"))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("HTTPS");
        }
    }

    @Test
    void rejectsTenantAdministration() {
        AiModelProviderService service = new AiModelProviderService(mock(BaseDao.class));
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(CurrentUser.tenantUser("user", "user", "tenant-a"))) {
            assertThatThrownBy(() -> service.beforePrepareInsert(provider("https://api.example.com/v1")))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("super administrators");
        }
    }

    private static void insert(AiModelProviderService service, String url) {
        AiModelProvider provider = provider(url);
        service.beforePrepareInsert(provider);
        service.beforeInsert(provider);
    }

    private static AiModelProvider provider(String url) {
        AiModelProvider provider = new AiModelProvider();
        provider.setId("test_provider");
        provider.setTitle("Test provider");
        provider.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        provider.setBaseUrl(url);
        return provider;
    }
}
