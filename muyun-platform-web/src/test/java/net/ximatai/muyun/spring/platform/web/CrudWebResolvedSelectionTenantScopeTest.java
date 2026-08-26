package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.web.MutationTenantScopeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CrudWebResolvedSelectionTenantScopeTest {
    @AfterEach
    void clearRequestScopes() {
        RequestContextHolder.resetRequestAttributes();
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void shouldResolveTenantBeforeTheCreateScopeIsEntered() {
        SelectionController controller = controller("tenant:tenant-a", "tenant-a");

        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"));
             TenantContext.Scope tenant = TenantContext.system("test")) {
            bindSelectionHeader("tenant:tenant-a");

            CrudWebRuntimeSupport.ResolvedSelectionTenantScope scope = CrudWebRuntimeSupport
                    .resolvedSelectionTenantScopeForCreate(controller).orElseThrow();
            String actualTenant = MutationTenantScopeExecutor.forAuthoritativeTenantScope(scope.tenantId(),
                    () -> TenantContext.currentTenantId().orElse(null));

            assertThat(scope.tenantId()).isEqualTo("tenant-a");
            assertThat(actualTenant).isEqualTo("tenant-a");
        }
    }

    @Test
    void shouldKeepPlatformSelectionInSystemScopeInsteadOfFallingBackToRecordScope() {
        SelectionController controller = controller("platform", null);

        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"));
             TenantContext.Scope tenant = TenantContext.system("test")) {
            bindSelectionHeader("platform");

            CrudWebRuntimeSupport.ResolvedSelectionTenantScope scope = CrudWebRuntimeSupport
                    .resolvedSelectionTenantScopeForCreate(controller).orElseThrow();
            boolean systemScope = MutationTenantScopeExecutor.forAuthoritativeTenantScope(scope.tenantId(),
                    TenantContext::isSystem);

            assertThat(scope.tenantId()).isNull();
            assertThat(systemScope).isTrue();
        }
    }

    @Test
    void shouldRejectAResolvedTenantThatIsNotANonBlankStringOrNull() {
        SelectionController controller = new SelectionController(new PageSelectionContextResolverRegistry(List.of(
                resolver("tenant:broken", 42)
        )));

        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"));
             TenantContext.Scope tenant = TenantContext.system("test")) {
            bindSelectionHeader("tenant:broken");

            assertThatThrownBy(() -> CrudWebRuntimeSupport.resolvedSelectionTenantScopeForCreate(controller))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tenantId");
        }
    }

    @Test
    void shouldLeaveModulesWithoutAResolvedTenantBindingOnTheirExistingCreatePath() {
        CrudAbility<TestRecord> service = mock(CrudAbility.class);
        CrudWeb<TestRecord, CrudAbility<TestRecord>> controller = new CrudWeb<>() {
            @Override public CrudAbility<TestRecord> service() { return service; }
            @Override public String webScopeName() { return "test.record"; }
        };

        assertThat(CrudWebRuntimeSupport.resolvedSelectionTenantScopeForCreate(controller)).isEmpty();
    }

    private SelectionController controller(String selectionKey, String tenantId) {
        return new SelectionController(new PageSelectionContextResolverRegistry(List.of(resolver(selectionKey, tenantId))));
    }

    private PageSelectionContextResolver resolver(String expectedKey, Object tenantId) {
        return new PageSelectionContextResolver() {
            @Override public String selectionKind() { return "roleScope"; }

            @Override public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
                if (!expectedKey.equals(request.selectionKey())) {
                    throw new IllegalArgumentException("unexpected selection key");
                }
                return new ResolvedPageSelectionContext("roleScope", request.selectionKey(),
                        Map.of("tenantId", PageContextValue.of(tenantId)));
            }
        };
    }

    private void bindSelectionHeader(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-MuYun-Page-Selection", "{\"kind\":\"roleScope\",\"key\":\"" + key + "\"}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static final class SelectionController implements CrudWeb<TestRecord, CrudAbility<TestRecord>> {
        private final CrudAbility<TestRecord> service = mock(CrudAbility.class);
        private final PageSelectionContextResolverRegistry resolvers;

        private SelectionController(PageSelectionContextResolverRegistry resolvers) {
            this.resolvers = resolvers;
        }

        @Override public CrudAbility<TestRecord> service() { return service; }
        @Override public String webScopeName() { return "iam.role"; }
        @Override public PageSelectionContextResolverRegistry pageSelectionContextResolvers() { return resolvers; }
        @Override public List<PageContextBindingDefinition> pageSelectionContextBindings() {
            return List.of(PageContextBindingDefinition.resolvedSelection("roleScope",
                    PageContextTarget.MUTATION_CONSTRAINT, "tenantId"));
        }
    }

    private static final class TestRecord extends StandardEntity {
    }
}
