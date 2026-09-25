package net.ximatai.muyun.spring.iam.position;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.util.Preconditions;
import org.springframework.stereotype.Service;

@Service
public class PositionCategoryService extends TenantActiveScopedService<PositionCategory> implements
        SoftDeleteAbility<PositionCategory>,
        EnableAbility<PositionCategory>,
        TreeAbility<PositionCategory>,
        ReferenceAbility<PositionCategory> {
    public static final String MODULE_ALIAS = "iam.position_category";
    private final PositionCategoryDao positionCategoryDao;

    public PositionCategoryService(PositionCategoryDao positionCategoryDao,
                                   ActiveTenantVerifier activeTenantVerifier) {
        super(MODULE_ALIAS, PositionCategory.class, positionCategoryDao, activeTenantVerifier);
        this.positionCategoryDao = positionCategoryDao;
    }

    @Override
    public void beforeDelete(String id) {
        String tenantId = requireActiveTenantMutationContext();
        String categoryId = Preconditions.requireText(id, "positionCategoryId");
        long childCategories = positionCategoryDao.count(Criteria.of()
                .eq(PlatformAbilityFields.TREE_PARENT_FIELD, categoryId)
                .eq(StandardEntitySchema.TENANT_ID_FIELD, tenantId)
                .eq(StandardEntitySchema.DELETED_FIELD, Boolean.FALSE));
        if (childCategories > 0) {
            throw BusinessExceptions.warning("iam.position-category.delete-has-children",
                    "该岗位分类下仍有子分类，不能删除");
        }

    }

}
