package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.platform.web.TreeManagementPageDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationWebControllerTest {
    @Test
    void shouldDeclareTenantNavigatorOnTheStandardTreePage() {
        TreeManagementPageDefinition page = (TreeManagementPageDefinition) new OrganizationWebController()
                .moduleUiDefinition().page();

        assertThat(page.navigator().levels()).singleElement().satisfies(level -> {
            assertThat(level.key()).isEqualTo("tenant");
            assertThat(level.sourceModuleAlias()).isEqualTo("iam.tenant");
        });
        assertThat(page.navigator().contextBindings()).hasSize(2);
        assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("title", "code", "parentId", "enabled");
    }

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
