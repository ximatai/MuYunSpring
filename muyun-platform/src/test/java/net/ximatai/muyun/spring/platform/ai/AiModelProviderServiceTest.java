package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    @SuppressWarnings("unchecked")
    void rejectsTheUnregisteredLegacyLmStudioProviderId() {
        BaseDao<AiModelProvider, String> dao = mock(BaseDao.class);
        AiModelProvider registered = provider("http://127.0.0.1:1234/v1");
        registered.setId(AiModelProviderService.LM_STUDIO_ID);
        registered.setEnabled(Boolean.TRUE);
        when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation -> {
            Criteria criteria = invocation.getArgument(0);
            boolean requestsCanonicalId = criteria.getClauses().stream()
                    .anyMatch(clause -> "id".equals(clause.getField())
                            && clause.getValues().contains(AiModelProviderService.LM_STUDIO_ID));
            return requestsCanonicalId ? List.of(registered) : List.of();
        });
        AiModelProviderService service = new AiModelProviderService(dao);

        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            assertThatThrownBy(() -> service.requireEnabled("lmStudio"))
                    .isInstanceOf(PlatformException.class)
                    .hasMessage("AI model provider is unavailable: lmStudio");
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
