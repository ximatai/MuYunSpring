package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PageContextMutationConstraintsTest {
    private static final List<PageContextBindingDefinition> TENANT_CONSTRAINT = List.of(
            PageContextBindingDefinition.session("tenantId", PageContextTarget.MUTATION_CONSTRAINT, "tenantId"));

    @Test
    void stampsCreateWithTheAuthenticatedTenantInsteadOfTrustingTheRequestBody() {
        TenantRecord record = new TenantRecord("untrusted-tenant");

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("u1", "User", "xcmg"))) {
            PageContextMutationConstraints.applyForCreate(record, TENANT_CONSTRAINT);
        }

        assertThat(record.getTenantId()).isEqualTo("xcmg");
    }

    @Test
    void rejectsUpdateWhenTheStoredRecordIsOutsideTheAuthenticatedTenant() {
        TenantRecord incoming = new TenantRecord("xcmg");
        TenantRecord existing = new TenantRecord("another-tenant");

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("u1", "User", "xcmg"))) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    PageContextMutationConstraints.applyForUpdate(incoming, existing, TENANT_CONSTRAINT));
        }
    }

    @Test
    void refusesToTreatBrowserNavigatorSelectionAsAMutationAuthority() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PageContextBindingDefinition(
                PageContextSource.NAVIGATOR, "category", PageContextTarget.MUTATION_CONSTRAINT,
                "categoryId", null));
    }

    @Test
    void permitsOnlyServerResolvedSelectionAsTheAdditionalMutationAuthority() {
        PageContextBindingDefinition binding = PageContextBindingDefinition.resolvedSelection(
                "roleScope", PageContextTarget.MUTATION_CONSTRAINT, "ownerScopeType");

        assertThat(binding.source()).isEqualTo(PageContextSource.RESOLVED_SELECTION);
    }

    @Test
    void declaresSeveralResolvedSelectionFieldsWithoutRepeatingTheTrustSource() {
        List<PageContextBindingDefinition> bindings = PageContextBindingDefinition.resolvedSelectionFields(
                "roleScope", PageContextTarget.MUTATION_CONSTRAINT,
                "ownerScopeType", "ownerScopeId", "tenantId");

        assertThat(bindings).allSatisfy(binding -> {
            assertThat(binding.source()).isEqualTo(PageContextSource.RESOLVED_SELECTION);
            assertThat(binding.sourceKey()).isEqualTo("roleScope");
            assertThat(binding.target()).isEqualTo(PageContextTarget.MUTATION_CONSTRAINT);
        });
        assertThat(bindings).extracting(PageContextBindingDefinition::targetKey)
                .containsExactly("ownerScopeType", "ownerScopeId", "tenantId");
    }

    @Test
    void stampsResolvedSelectionNullInsteadOfTreatingItAsAnUnresolvedField() {
        TenantRecord record = new TenantRecord("untrusted");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-MuYun-Page-Selection", "{\"kind\":\"roleScope\",\"key\":\"platform\"}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        PageSelectionContextResolver resolver = new PageSelectionContextResolver() {
            @Override public String selectionKind() { return "roleScope"; }

            @Override public ResolvedPageSelectionContext resolve(PageSelectionContextRequest selectionRequest) {
                assertThat(selectionRequest.action()).isEqualTo(PlatformAction.CREATE);
                return new ResolvedPageSelectionContext("roleScope", "platform",
                        Map.of("tenantId", PageContextValue.of(null)));
            }
        };
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            PageContextMutationConstraints.applyForCreate(record, List.of(
                    PageContextBindingDefinition.resolvedSelection("roleScope",
                            PageContextTarget.MUTATION_CONSTRAINT, "tenantId")), "iam.role", PlatformAction.CREATE,
                    new PageSelectionContextResolverRegistry(List.of(resolver)));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        assertThat(record.getTenantId()).isNull();
    }

    static final class TenantRecord {
        private String tenantId;

        TenantRecord(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }
    }
}
