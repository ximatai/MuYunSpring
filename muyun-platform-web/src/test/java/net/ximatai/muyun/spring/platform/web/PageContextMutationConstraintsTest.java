package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.junit.jupiter.api.Test;

import java.util.List;

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
