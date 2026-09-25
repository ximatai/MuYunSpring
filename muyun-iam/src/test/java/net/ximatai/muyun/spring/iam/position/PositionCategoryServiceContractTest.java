package net.ximatai.muyun.spring.iam.position;

import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PositionCategoryServiceContractTest {
    @Test
    void shouldExposeStableModuleAlias() {
        PositionCategoryService service = service(mock(PositionCategoryDao.class));

        assertThat(service.getModuleAlias()).isEqualTo("iam.position_category");
    }

    @Test
    void shouldFillPositionCategoryDefaultsThroughCrudAbility() {
        PositionCategoryDao dao = mock(PositionCategoryDao.class);
        when(dao.insert(any())).thenReturn("position-category-1");
        ActiveTenantVerifier tenantVerifier = activeTenantVerifier();
        PositionCategoryService service = new PositionCategoryService(dao, tenantVerifier);
        PositionCategory category = category(" TECH ", " Technology ");
        category.setDescription(" ");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.insert(category);
        }

        assertThat(category.getCode()).isEqualTo("TECH");
        assertThat(category.getTitle()).isEqualTo("Technology");
        assertThat(category.getEnabled()).isTrue();
        assertThat(category.getParentId()).isEqualTo(TreeAbility.ROOT_ID);
        assertThat(category.getTenantId()).isEqualTo("tenant_a");
        assertThat(category.getDescription()).isNull();
        verify(tenantVerifier).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldRequireTenantContextForPositionCategoryMutation() {
        PositionCategoryService service = service(mock(PositionCategoryDao.class));

        assertThatThrownBy(() -> service.insert(category("TECH", "Technology")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("tenant context");
    }

    @Test
    void shouldRequirePositionCategoryCodeAndTitle() {
        PositionCategoryService service = service(mock(PositionCategoryDao.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(category(" ", "Technology")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("code");
            assertThatThrownBy(() -> service.insert(category("TECH", " ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("title");
        }
    }

    @Test
    void shouldApplyInheritedTitleRulesOnUpdate() {
        PositionCategoryDao dao = mock(PositionCategoryDao.class);
        PositionCategoryService service = spy(service(dao));
        PositionCategory existing = category("TECH", "Technology");
        existing.setId("category-1");
        existing.setParentId(TreeAbility.ROOT_ID);
        existing.setTenantId("tenant_a");
        existing.setVersion(1);
        doReturn(existing).when(service).selectExistingForScopedMutation(any());
        PositionCategory update = category("TECH", "  ");
        update.setId(existing.getId());
        update.setParentId(TreeAbility.ROOT_ID);
        update.setVersion(1);
        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.update(update))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        }
    }

    @Test
    void shouldRejectDeletingPositionCategoryWithChildCategories() {
        PositionCategoryDao categoryDao = mock(PositionCategoryDao.class);
        when(categoryDao.count(any())).thenReturn(1L);
        PositionDao positionDao = mock(PositionDao.class);
        PositionCategoryService service = new PositionCategoryService(categoryDao,
                activeTenantVerifier());

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeDelete("category-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "iam.position-category.delete-has-children")
                    .hasMessage("该岗位分类下仍有子分类，不能删除");
        }

        verify(categoryDao).count(any());
    }

    private PositionCategory category(String code, String title) {
        PositionCategory category = new PositionCategory();
        category.setCode(code);
        category.setTitle(title);
        return category;
    }

    private ActiveTenantVerifier activeTenantVerifier() {
        return mock(ActiveTenantVerifier.class);
    }

    private PositionCategoryService service(PositionCategoryDao categoryDao) {
        return new PositionCategoryService(categoryDao, activeTenantVerifier());
    }
}
