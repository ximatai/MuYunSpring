package net.ximatai.muyun.spring.iam.department;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.DataScopeFieldMapping;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.util.Preconditions;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DepartmentService extends TenantActiveScopedService<Department> implements
        SoftDeleteAbility<Department>,
        EnableAbility<Department>,
        TreeAbility<Department>,
        DataScopeAbility<Department>,
        ReferenceAbility<Department> {

    public static final String MODULE_ALIAS = "iam.department";
    private static final DataScopeFieldMapping DATA_SCOPE_FIELD_MAPPING =
            DataScopeFieldMapping.of(null, "organizationId", "id");

    public DepartmentService(DepartmentDao departmentDao,
                             ActiveTenantVerifier activeTenantVerifier) {
        super(MODULE_ALIAS, Department.class, departmentDao, activeTenantVerifier);
    }

    @Override
    public DataScopeFieldMapping dataScopeFieldMapping() {
        return DATA_SCOPE_FIELD_MAPPING;
    }


    public List<Department> rootDepartments(String organizationId) {
        return departmentChildren(organizationId, TreeAbility.ROOT_ID);
    }

    public List<Department> departmentChildren(String organizationId, String parentId) {
        String validOrganizationId = Preconditions.requireText(organizationId, "organizationId");
        return children(organizationScope(validOrganizationId), parentId);
    }

    public List<Department> departmentChildrenForAction(PlatformAction action, String organizationId, String parentId) {
        String validOrganizationId = Preconditions.requireText(organizationId, "organizationId");
        String validParentId = Preconditions.requireText(parentId, "parentId");
        Criteria criteria = scopedTreeCriteria(organizationScope(validOrganizationId), validParentId);
        return listForAction(action, criteria, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public List<String> selfAndDescendantIds(String organizationId, String departmentId) {
        return selfAndDescendantIds(organizationScope(organizationId),
                Preconditions.requireText(departmentId, "departmentId"));
    }

    public void moveInDepartmentTree(String id, String previousId, String nextId, String parentId) {
        Department moving = select(id);
        if (moving == null) {
            throw new PlatformException("Cannot move missing department: " + id);
        }
        moveInTree(organizationScope(moving.getOrganizationId()), id, previousId, nextId, parentId);
    }

    private Criteria organizationScope(String organizationId) {
        return Criteria.of().eq("organizationId", Preconditions.requireText(organizationId, "organizationId"));
    }
}
