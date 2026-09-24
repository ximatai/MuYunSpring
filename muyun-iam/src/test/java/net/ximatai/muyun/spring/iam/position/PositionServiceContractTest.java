package net.ximatai.muyun.spring.iam.position;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PositionServiceContractTest {
    @Test
    void shouldExposeStableModuleAlias() {
        PositionService service = new PositionService(mock(PositionDao.class), activeTenantVerifier());

        assertThat(service.getModuleAlias()).isEqualTo("iam.position");
    }

    @Test
    void shouldFillPositionDefaultsThroughCrudAbility() {
        PositionDao dao = mock(PositionDao.class);
        when(dao.insert(any())).thenReturn("position-1");
        ActiveTenantVerifier tenantVerifier = activeTenantVerifier();
        PositionService service = new PositionService(dao, tenantVerifier);
        Position position = position("SALES_MANAGER", "Sales Manager");
        position.setDescription(" ");
        position.setCategoryId("category-1");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.insert(position);
        }

        assertThat(position.getEnabled()).isTrue();
        assertThat(position.getTenantId()).isEqualTo("tenant_a");
        assertThat(position.getCategoryId()).isEqualTo("category-1");
        assertThat(position.getDescription()).isNull();
        verify(tenantVerifier).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldRequireTenantContextForPositionMutation() {
        PositionService service = new PositionService(mock(PositionDao.class), activeTenantVerifier());

        assertThatThrownBy(() -> service.insert(position("SALES_MANAGER", "Sales Manager")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("tenant context");
    }

    @Test
    void shouldRequirePositionCategoryCodeAndTitle() {
        PositionService service = new PositionService(mock(PositionDao.class), activeTenantVerifier());

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(position("SALES_MANAGER", "Sales Manager")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positionCategoryId");
            assertThatThrownBy(() -> service.insert(position("category-1", " ", "Sales Manager")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positionCode");
            assertThatThrownBy(() -> service.insert(position("category-1", "SALES_MANAGER", " ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positionTitle");
        }
    }

    @Test
    void shouldUseCategorySortScopeForPositionCatalog() {
        PositionService service = new PositionService(mock(PositionDao.class), activeTenantVerifier());
        Position sameCategoryLeft = position("FINANCE_REVIEWER", "Finance Reviewer");
        sameCategoryLeft.setCategoryId("category-1");
        Position sameCategoryRight = position("FINANCE_MANAGER", "Finance Manager");
        sameCategoryRight.setCategoryId("category-1");
        Position anotherCategory = position("TECH_LEAD", "Tech Lead");
        anotherCategory.setCategoryId("category-2");

        assertThat(service.sortScope(sameCategoryLeft).isEmpty()).isFalse();
        service.validateSortScope(sameCategoryLeft, sameCategoryRight);
        assertThatThrownBy(() -> service.validateSortScope(sameCategoryLeft, anotherCategory))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same category");
    }

    private Position position(String code, String title) {
        Position position = new Position();
        position.setCode(code);
        position.setTitle(title);
        return position;
    }

    private Position position(String categoryId, String code, String title) {
        Position position = position(code, title);
        position.setCategoryId(categoryId);
        return position;
    }

    private ActiveTenantVerifier activeTenantVerifier() {
        return mock(ActiveTenantVerifier.class);
    }

    private PositionCategoryService positionCategoryService() {
        return mock(PositionCategoryService.class);
    }
}
