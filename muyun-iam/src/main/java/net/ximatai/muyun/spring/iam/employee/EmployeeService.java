package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.child.AggregateChildFormulaDefinition;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.ExternalQueryValueSource;
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjection;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjectionContributor;
import net.ximatai.muyun.spring.ability.reference.ReferencePath;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.platform.DataScopeFieldMapping;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class EmployeeService extends TenantActiveScopedService<Employee> implements
        SoftDeleteAbility<Employee>,
        RecycleBinAbility<Employee>,
        EnableAbility<Employee>,
        SortAbility<Employee>,
        ChildrenAbility<Employee>,
        ReferenceAbility<Employee>,
        DataScopeAbility<Employee>,
        QueryAbility<Employee>,
        ModuleReadProjectionContributor {
    public static final String MODULE_ALIAS = "iam.employee";
    private static final DataScopeFieldMapping DATA_SCOPE_FIELD_MAPPING =
            DataScopeFieldMapping.of(null, "organizationId", "departmentId");

    private final DepartmentService departmentService;
    public EmployeeService(EmployeeDao employeeDao,
                           ActiveTenantVerifier activeTenantVerifier,
                           DepartmentService departmentService) {
        super(MODULE_ALIAS, Employee.class, employeeDao, activeTenantVerifier);
        this.departmentService = departmentService;
    }

    @Override
    public List<AggregateChildFormulaDefinition> aggregateChildFormulaDefinitions() {
        return List.of(EmployeeFormulas.primaryPositionExclusive());
    }

    @Override
    public DataScopeFieldMapping dataScopeFieldMapping() {
        return DATA_SCOPE_FIELD_MAPPING;
    }

    @Override
    public void normalizeBeforeMutation(Employee employee) {
        employee.setOrganizationId(Preconditions.requireText(employee.getOrganizationId(), "organizationId"));
        employee.setDepartmentId(Preconditions.requireText(employee.getDepartmentId(), "departmentId"));
        employee.setEmployeeNo(Preconditions.requireText(employee.getEmployeeNo(), "employeeNo"));
        employee.setTitle(Preconditions.requireText(employee.getTitle(), "employeeName"));
        employee.setGender(normalizeBlank(employee.getGender()));
        employee.setMobile(normalizeBlank(employee.getMobile()));
        employee.setEmail(normalizeBlank(employee.getEmail()));
    }

    /** Persists only the contact and avatar fields an authenticated employee may maintain for themself. */
    public int updateSelfManagedProfile(String employeeId, String mobile, String email, String avatarAssetId) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("employee id is required");
        }
        Employee employee = requireEnabled(employeeId,
                "当前职员已停用，不能维护个人资料");
        employee.setMobile(normalizeSelfManagedField(mobile, 32, "mobile"));
        employee.setEmail(normalizeSelfManagedField(email, 128, "email"));
        employee.setAvatarAssetId(normalizeSelfManagedField(avatarAssetId, 32, "avatarAssetId"));
        return update(employee);
    }

    private String normalizeSelfManagedField(String value, int maxLength, String field) {
        String normalized = normalizeBlank(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptor.builder(MODULE_ALIAS)
                .field(QueryField.of("id", QueryOperator.EQ, QueryOperator.IN).withTitle("ID"))
                .field(QueryField.of("tenantId", QueryOperator.EQ, QueryOperator.IN).withTitle("租户"))
                .field(QueryField.of("organizationId", QueryOperator.EQ, QueryOperator.IN).withTitle("所属机构"))
                .field(QueryField.of("departmentId", QueryOperator.EQ, QueryOperator.IN).withTitle("所属部门"))
                .field(QueryField.of("enabled", QueryValueType.BOOLEAN, QueryOperator.EQ).withTitle("启用状态"))
                .field(QueryField.of("employeeNo", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("职员编号").withQuickSearch().withSortable())
                .field(QueryField.of("title", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("职员姓名").withQuickSearch().withSortable())
                .field(QueryField.of("gender", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("性别"))
                .field(QueryField.of("mobile", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("手机号").withQuickSearch())
                .field(QueryField.of("email", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("邮箱").withQuickSearch())
                .field(QueryField.of("sortOrder", QueryValueType.INTEGER, QueryOperator.EQ)
                        .withTitle("排序号").withSortable())
                .field(QueryField.of("createdAt", QueryValueType.INSTANT, QueryOperator.GTE, QueryOperator.LTE,
                                QueryOperator.BETWEEN)
                        .withTitle("创建时间")
                        .withSortable())
                .field(QueryField.of("updatedAt", QueryValueType.INSTANT, QueryOperator.GTE, QueryOperator.LTE,
                                QueryOperator.BETWEEN)
                        .withTitle("更新时间")
                        .withSortable())
                // The organization navigator is an explicit list scope, not a client-side filter.
                .externalCriteria("organizationId", QueryValueType.STRING, ExternalQueryValueSource.PAGE_CONTEXT, value -> Criteria.of()
                        .eq("organizationId", requireText(text(value), "organizationId")))
                .externalCriteria("departmentScope", QueryValueType.JSON, ExternalQueryValueSource.PAGE_CONTEXT,
                        this::departmentScopeCriteria)
                .defaultSort(net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"))
                .defaultSort(net.ximatai.muyun.database.core.orm.Sort.asc("employeeNo"))
                .build();
    }

    @Override
    public List<ModuleReadProjection> moduleReadProjections() {
        return List.of(
                ModuleReadProjection.declared("organizationTitle", false, true),
                ModuleReadProjection.filterableOnly(
                        ReferencePath.inverseOne(EmployeeAccount::getEmployeeId)
                                .then(EmployeeAccount::getUserId)
                                .select(UserAccount::getUsername),
                        "username"),
                ModuleReadProjection.exists(
                        ReferencePath.inverseOne(EmployeeAccount::getEmployeeId)
                                .select(EmployeeAccount::getId),
                        "accountBound")
        );
    }

    private Criteria departmentScopeCriteria(Object value) {
        Map<?, ?> scope = requireMap(value, "departmentScope");
        String organizationId = text(scope.get("organizationId"));
        String departmentId = text(scope.get("departmentId"));
        boolean includeChildren = Boolean.TRUE.equals(scope.get("includeChildren"));
        Criteria criteria = Criteria.of();
        if (organizationId != null) {
            criteria.eq("organizationId", organizationId);
        }
        if (departmentId == null) {
            return criteria;
        }
        if (!includeChildren) {
            return criteria.eq("departmentId", departmentId);
        }
        String validOrganizationId = requireText(organizationId, "departmentScope.organizationId");
        List<String> ids = departmentService.selfAndDescendantIds(validOrganizationId, departmentId);
        return criteria.in("departmentId", ids.isEmpty() ? List.of("__missing_department__") : ids);
    }

    private Map<?, ?> requireMap(Object value, String label) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(label + " must be an object");
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
