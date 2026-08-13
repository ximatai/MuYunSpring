package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationWebControllerTest {
    @Test
    void shouldResolveOrganizationTenantForSystemScopedMutations() {
        OrganizationService service = mock(OrganizationService.class);
        OrganizationWebController controller = new OrganizationWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        Organization existing = organization("tenant-a");
        when(service.select("organization-1")).thenReturn(existing);

        assertThat(controller.tenantIdForCreate(organization("tenant-b"))).contains("tenant-b");
        assertThat(controller.tenantIdForUpdate("organization-1", organization("tenant-b"))).contains("tenant-a");
        assertThat(controller.tenantIdForExistingRecord("organization-1")).contains("tenant-a");
    }

    private static Organization organization(String tenantId) {
        Organization organization = new Organization();
        organization.setTenantId(tenantId);
        return organization;
    }
}
