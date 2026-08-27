package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.form.FormAbility;
import net.ximatai.muyun.spring.ability.form.FormDescriptor;
import net.ximatai.muyun.spring.ability.form.FormField;
import net.ximatai.muyun.spring.ability.form.FormValueType;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeCandidate;
import net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeCatalogResolver;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformAliasRules;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeePosition;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class RoleService extends TenantActiveScopedService<Role> implements
        SoftDeleteAbility<Role>,
        EnableAbility<Role>,
        SortAbility<Role>,
        ReferenceAbility<Role>,
        FormAbility<Role>,
        QueryAbility<Role> {
    public static final String MODULE_ALIAS = "iam.role";
    public static final String PLATFORM_SUPER_ADMIN_ROLE_ID = "platform.role.super_admin";
    public static final String PLATFORM_SUPER_ADMIN_ROLE_TITLE = "平台超级管理员";
    public static final String TENANT_ADMIN_ROLE_TITLE = "租户管理员";

    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final AccountRoleGrantDao accountRoleGrantDao;
    private final EmploymentRoleGrantDao employmentRoleGrantDao;
    private final RoleActionDao roleActionDao;
    private final RoleDataGrantActionDao roleDataGrantActionDao;
    private final RoleActionGrantVerifier grantVerifier;
    private final UserAccountService userAccountService;
    private final EmployeeService employeeService;
    private final EmployeePositionService employeePositionService;
    private final EmployeeAccountService employeeAccountService;
    private final OrganizationService organizationService;
    private ReferenceDependencyScopeCatalogResolver referenceDependencyScopeCatalogResolver;
    private TenantApplicationService tenantApplicationService;

    public RoleService(RoleDao roleDao,
                       AccountRoleGrantDao accountRoleGrantDao,
                       EmploymentRoleGrantDao employmentRoleGrantDao,
                       RoleActionDao roleActionDao,
                       ActiveTenantVerifier activeTenantVerifier) {
        this(roleDao, accountRoleGrantDao, employmentRoleGrantDao, roleActionDao, activeTenantVerifier,
                RoleActionGrantVerifier.platformActionsOnly(), null, null, null, null,
                (OrganizationService) null, null);
    }

    @Autowired
    public RoleService(RoleDao roleDao,
                       AccountRoleGrantDao accountRoleGrantDao,
                       EmploymentRoleGrantDao employmentRoleGrantDao,
                       RoleActionDao roleActionDao,
                       RoleDataGrantActionDao roleDataGrantActionDao,
                       TenantService tenantService,
                       RoleActionGrantVerifier grantVerifier,
                       UserAccountService userAccountService,
                       EmployeeService employeeService,
                       EmployeePositionService employeePositionService,
                       EmployeeAccountService employeeAccountService,
                       ObjectProvider<OrganizationService> organizationService) {
        this(roleDao, accountRoleGrantDao, employmentRoleGrantDao, roleActionDao, tenantService,
                grantVerifier, userAccountService, employeeService, employeePositionService, employeeAccountService,
                organizationService == null ? null : organizationService.getIfAvailable(), roleDataGrantActionDao);
    }

    public RoleService(RoleDao roleDao,
                       AccountRoleGrantDao accountRoleGrantDao,
                       EmploymentRoleGrantDao employmentRoleGrantDao,
                       RoleActionDao roleActionDao,
                       ActiveTenantVerifier activeTenantVerifier,
                       RoleActionGrantVerifier grantVerifier,
                       UserAccountService userAccountService,
                       EmployeeService employeeService,
                       EmployeePositionService employeePositionService,
                       EmployeeAccountService employeeAccountService) {
        this(roleDao, accountRoleGrantDao, employmentRoleGrantDao, roleActionDao, activeTenantVerifier,
                grantVerifier, userAccountService, employeeService, employeePositionService, employeeAccountService,
                (OrganizationService) null, null);
    }

    public RoleService(RoleDao roleDao,
                       AccountRoleGrantDao accountRoleGrantDao,
                       EmploymentRoleGrantDao employmentRoleGrantDao,
                       RoleActionDao roleActionDao,
                       ActiveTenantVerifier activeTenantVerifier,
                       RoleActionGrantVerifier grantVerifier,
                       UserAccountService userAccountService,
                       EmployeeService employeeService,
                       EmployeePositionService employeePositionService,
                       EmployeeAccountService employeeAccountService,
                       OrganizationService organizationService) {
        this(roleDao, accountRoleGrantDao, employmentRoleGrantDao, roleActionDao, activeTenantVerifier,
                grantVerifier, userAccountService, employeeService, employeePositionService, employeeAccountService,
                organizationService, null);
    }

    public RoleService(RoleDao roleDao,
                       AccountRoleGrantDao accountRoleGrantDao,
                       EmploymentRoleGrantDao employmentRoleGrantDao,
                       RoleActionDao roleActionDao,
                       ActiveTenantVerifier activeTenantVerifier,
                       RoleActionGrantVerifier grantVerifier,
                       UserAccountService userAccountService,
                       EmployeeService employeeService,
                       EmployeePositionService employeePositionService,
                       EmployeeAccountService employeeAccountService,
                       OrganizationService organizationService,
                       RoleDataGrantActionDao roleDataGrantActionDao) {
        super(MODULE_ALIAS, Role.class, roleDao, activeTenantVerifier);
        this.accountRoleGrantDao = Objects.requireNonNull(accountRoleGrantDao, "accountRoleGrantDao must not be null");
        this.employmentRoleGrantDao = Objects.requireNonNull(employmentRoleGrantDao,
                "employmentRoleGrantDao must not be null");
        this.roleActionDao = Objects.requireNonNull(roleActionDao, "roleActionDao must not be null");
        this.roleDataGrantActionDao = roleDataGrantActionDao;
        this.grantVerifier = Objects.requireNonNull(grantVerifier, "grantVerifier must not be null");
        this.userAccountService = userAccountService;
        this.employeeService = employeeService;
        this.employeePositionService = employeePositionService;
        this.employeeAccountService = employeeAccountService;
        this.organizationService = organizationService;
    }

    @Autowired(required = false)
    void setReferenceDependencyScopeCatalogResolver(
            ReferenceDependencyScopeCatalogResolver referenceDependencyScopeCatalogResolver) {
        this.referenceDependencyScopeCatalogResolver = referenceDependencyScopeCatalogResolver;
    }

    @Autowired(required = false)
    void setTenantApplicationService(TenantApplicationService tenantApplicationService) {
        this.tenantApplicationService = tenantApplicationService;
    }

    @Override
    public FormDescriptor formDescriptor() {
        return FormDescriptor.builder(MODULE_ALIAS)
                .title("角色")
                .field(FormField.of("assignmentType").withTitle("授权层级").asRequired())
                .field(FormField.of("roleKind").withTitle("角色类型").asRequired())
                .field(FormField.of("title").withTitle("角色名称").asRequired())
                .field(FormField.of("memberRoleIds").withTitle("成员角色"))
                .field(FormField.of("ownerScopeType").withTitle("定义归属").asRequired())
                .field(FormField.of("ownerScopeId").withTitle("归属对象"))
                .field(FormField.of("ownerScopeKey").withTitle("归属键").asReadOnly())
                .field(FormField.of("sharePolicy").withTitle("共享策略").asRequired())
                .field(FormField.of("builtIn", FormValueType.BOOLEAN).withTitle("内置角色").asReadOnly())
                .field(FormField.of("systemManaged", FormValueType.BOOLEAN).withTitle("系统托管").asReadOnly())
                .field(FormField.of("systemPurpose").withTitle("系统用途").asReadOnly())
                .field(FormField.of("description", FormValueType.TEXT).withTitle("角色描述"))
                .build();
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptor.builder(MODULE_ALIAS)
                .field(QueryField.of("id", QueryOperator.EQ, QueryOperator.IN).withTitle("ID"))
                .field(QueryField.of("assignmentType", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("授权层级"))
                .field(QueryField.of("roleKind", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("角色类型"))
                .field(QueryField.of("title", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("角色名称").withQuickSearch().withSortable())
                .field(QueryField.of("ownerScopeType", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("定义归属"))
                .field(QueryField.of("ownerScopeId", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("归属对象"))
                .field(QueryField.of("ownerScopeKey", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("归属键"))
                .field(QueryField.of("sharePolicy", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("共享策略"))
                .field(QueryField.of("enabled", QueryValueType.BOOLEAN, QueryOperator.EQ).withTitle("启用状态"))
                .field(QueryField.of("builtIn", QueryValueType.BOOLEAN, QueryOperator.EQ).withTitle("内置角色"))
                .field(QueryField.of("systemManaged", QueryValueType.BOOLEAN, QueryOperator.EQ).withTitle("系统托管"))
                .field(QueryField.of("systemPurpose", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("系统用途"))
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
                .defaultSort(Sort.asc("sortOrder"))
                .defaultSort(Sort.asc("title"))
                .build();
    }


    @Override
    public void normalizeBeforeMutation(Role role) {
        if (role.getAssignmentType() == null) {
            role.setAssignmentType(RoleAssignmentType.EMPLOYMENT);
        }
        if (role.getRoleKind() == null) {
            role.setRoleKind(RoleKind.STANDARD);
        }
        if (role.getRoleKind() == RoleKind.GROUP || role.getRoleKind() == RoleKind.DATA_GRANT) {
            role.setAssignmentType(RoleAssignmentType.EMPLOYMENT);
        }
        normalizeOwnerAndSharePolicy(role);
        if (role.getBuiltIn() == null) {
            role.setBuiltIn(false);
        }
        if (role.getSystemManaged() == null) {
            role.setSystemManaged(false);
        }
        if (role.getSystemPurpose() == null) {
            role.setSystemPurpose(RoleSystemPurpose.NONE);
        }
        if (role.getSystemPurpose() != RoleSystemPurpose.NONE && !Boolean.TRUE.equals(role.getSystemManaged())) {
            throw BusinessExceptions.warning("iam.role.system-purpose-system-managed-required",
                    "仅系统托管角色可以声明系统用途");
        }
        if (role.getRoleKind() == RoleKind.GROUP) {
            role.setMemberRoleIds(normalizeRoleIdCsv(role.getMemberRoleIds()));
            validateGroupMembers(role.getMemberRoleIds());
            validateGroupDataGrantUsage(role);
        } else {
            role.setMemberRoleIds(null);
        }
    }

    @Override
    public void beforePrepareInsert(Role role) {
        normalizeBeforeMutation(role);
        if (role.getOwnerScopeType() == RoleOwnerScopeType.PLATFORM) {
            requirePlatformRoleSystemContext();
            return;
        }
        requireActiveTenantMutationContext();
    }

    @Override
    public void beforeInsert(Role role) {
        requireSystemManagedMutationAllowed(role, "create");
    }

    public Role ensureSystemManagedTenantAdminRole(String tenantId,
                                                   String roleId,
                                                   String title,
                                                   String description) {
        String validTenantId = Preconditions.requireText(tenantId, "tenantId");
        String validRoleId = Preconditions.requireText(roleId, "roleId");
        String validTitle = Preconditions.requireText(title, "title");
        try (TenantContext.Scope ignored = TenantContext.use(validTenantId)) {
            Role existing = selectIgnoreSoftDelete(validRoleId);
            if (existing == null) {
                Role role = tenantAdminRole(validRoleId, validTitle, description);
                insert(role);
                return role;
            }
            return repairSystemManagedTenantAdminRole(existing, validTitle, description);
        }
    }

    public Role ensureSystemManagedOrganizationAdminRole(String tenantId,
                                                         String organizationId,
                                                         String roleId,
                                                         String title,
                                                         String description) {
        String validTenantId = Preconditions.requireText(tenantId, "tenantId");
        String validOrganizationId = Preconditions.requireText(organizationId, "organizationId");
        String validRoleId = Preconditions.requireText(roleId, "roleId");
        String validTitle = Preconditions.requireText(title, "title");
        try (TenantContext.Scope ignored = TenantContext.use(validTenantId)) {
            Role existing = selectIgnoreSoftDelete(validRoleId);
            if (existing == null) {
                Role role = organizationAdminRole(validRoleId, validOrganizationId, validTitle, description);
                insert(role);
                return role;
            }
            return repairSystemManagedOrganizationAdminRole(existing, validOrganizationId, validTitle, description);
        }
    }

    @Override
    public void beforeUpdate(Role role) {
        Role existing = role == null || role.getId() == null ? null : select(role.getId());
        if (existing != null) {
            normalizeLoadedRoleDefaults(existing);
        }
        if (existing != null && existing.getOwnerScopeType() == RoleOwnerScopeType.PLATFORM) {
            requirePlatformRoleSystemContext();
        } else {
            requireActiveTenantMutationContext();
        }
        normalizeBeforeMutation(role);
        requireSystemManagedMutationAllowed(existing, "update");
        requireSystemManagedMutationAllowed(role, "update");
        requireStructuralFieldsUnchanged(existing, role);
    }

    @Override
    public void beforeDelete(String id) {
        Role role = select(id);
        if (role != null && role.getOwnerScopeType() == RoleOwnerScopeType.PLATFORM) {
            requirePlatformRoleSystemContext();
        } else {
            requireActiveTenantMutationContext();
        }
        requireSystemManagedMutationAllowed(role, "delete");
    }

    /** Internal provisioning path for system-owned roles with a pre-established management scope. */
    String grantAccountRole(String roleId,
                            String userId,
                            ManagementScopeType managementScopeType,
                            String managementScopeId) {
        return grantAccountRoleResult(roleId, userId, managementScopeType, managementScopeId).grantId();
    }

    /**
     * Resolves the authority scope used by account-role binding. Platform roles are shared
     * definitions, so the target tenant is part of the binding command rather than an
     * implicit platform-wide grant.
     */
    public AccountRoleBindingScope resolveAccountRoleBindingScope(String roleId, String targetTenantId) {
        Role role = requireBindableRole(roleId);
        requireAccountRole(role);
        if (role.getOwnerScopeType() == RoleOwnerScopeType.PLATFORM) {
            if (role.getSharePolicy() != RoleSharePolicy.PLATFORM) {
                throw BusinessExceptions.warning("iam.role.platform-private-not-bindable",
                        "租户不能绑定平台私有角色");
            }
            if (targetTenantId == null || targetTenantId.isBlank()) {
                throw BusinessExceptions.warning("iam.role.account-binding-target-tenant-required",
                        "平台角色绑定用户前必须先选择目标租户");
            }
            String tenantId = targetTenantId.trim();
            verifyActiveTenant(tenantId);
            return new AccountRoleBindingScope(tenantId, ManagementScopeType.TENANT, tenantId);
        }
        String tenantId = Preconditions.requireText(role.getTenantId(),
                "tenantId is required for tenant or organization account role binding");
        verifyActiveTenant(tenantId);
        if (role.getOwnerScopeType() == RoleOwnerScopeType.ORGANIZATION) {
            return new AccountRoleBindingScope(tenantId, ManagementScopeType.ORGANIZATION,
                    Preconditions.requireText(role.getOwnerScopeId(), "ownerScopeId"));
        }
        return new AccountRoleBindingScope(tenantId, ManagementScopeType.TENANT, tenantId);
    }

    /**
     * Retained for candidate readers that only need the authoritative tenant. Platform
     * roles must still provide an explicit target tenant through the overload below.
     */
    public String resolveAccountRoleBindingTenant(String roleId) {
        return resolveAccountRoleBindingScope(roleId, null).tenantId();
    }

    public String resolveAccountRoleBindingTenant(String roleId, String targetTenantId) {
        return resolveAccountRoleBindingScope(roleId, targetTenantId).tenantId();
    }

    RoleGrantMutationResult grantAccountRoleResult(String roleId,
                                                   String userId,
                                                   ManagementScopeType managementScopeType,
                                                   String managementScopeId) {
        return grantAccountRoleIfAbsent(roleId, userId, managementScopeType, managementScopeId);
    }

    /** Creates an account-role grant from the role's authoritative binding scope. */
    public RoleGrantMutationResult grantAccountRoleResult(String roleId, String userId, String targetTenantId) {
        AccountRoleBindingScope scope = resolveAccountRoleBindingScope(roleId, targetTenantId);
        return grantAccountRoleIfAbsent(roleId, userId, scope.managementScopeType(), scope.managementScopeId());
    }

    public int revokeAccountRole(String roleId,
                                 String userId,
                                 ManagementScopeType managementScopeType,
                                 String managementScopeId) {
        Role role = requireBindableRole(roleId);
        requireAccountRole(role);
        requireSystemManagedMutationAllowed(role, "revoke account role");
        AccountRoleGrant grant = findAccountRoleGrant(role.getId(), userId, managementScopeType, managementScopeId);
        return grant == null ? 0 : accountRoleGrantDao.deleteById(grant.getId());
    }

    /** Deletes a grant from the role's authoritative binding scope. */
    public int deleteAccountRoleGrant(String roleId, String grantId, String targetTenantId) {
        AccountRoleBindingScope scope = resolveAccountRoleBindingScope(roleId, targetTenantId);
        return deleteAccountRoleGrant(roleId, grantId, scope);
    }

    /** Internal lifecycle path for system provisioning that already owns the scope. */
    int deleteAccountRoleGrant(String roleId, String grantId) {
        return deleteAccountRoleGrant(roleId, grantId, (AccountRoleBindingScope) null);
    }

    private int deleteAccountRoleGrant(String roleId, String grantId, AccountRoleBindingScope scope) {
        Role role = requireEnabledRole(roleId);
        requireSystemManagedMutationAllowed(role, "delete account role grant");
        AccountRoleGrant grant = accountRoleGrantDao.query(activeCriteria(Criteria.of()
                        .eq("id", Preconditions.requireText(grantId, "grantId"))),
                new PageRequest(0, 1)).stream().findFirst().orElse(null);
        if (grant == null || !SortAbility.sameValue(role.getId(), grant.getRoleId())) {
            throw BusinessExceptions.warning("iam.role.account-grant-role-mismatch",
                    "该账号角色绑定不属于当前角色");
        }
        if (scope != null && (grant.getManagementScopeType() != scope.managementScopeType()
                || !SortAbility.sameValue(grant.getManagementScopeId(), scope.managementScopeId()))) {
            throw BusinessExceptions.warning("iam.role.account-grant-scope-mismatch",
                    "该账号角色绑定不属于当前管理范围");
        }
        return accountRoleGrantDao.deleteById(grant.getId());
    }

    public List<AccountRoleGrant> accountRoleGrants(String roleId) {
        Role role = requireEnabledRole(roleId);
        requireAccountRole(role);
        return accountRoleGrantDao.query(activeCriteria(Criteria.of()
                        .eq("roleId", role.getId())
                        .eq("enabled", Boolean.TRUE)), ALL);
    }

    /** Lists grants in the scope currently being managed by the binding drawer. */
    public List<AccountRoleGrant> accountRoleGrants(String roleId, String targetTenantId) {
        Role role = requireEnabledRole(roleId);
        requireAccountRole(role);
        AccountRoleBindingScope scope = resolveAccountRoleBindingScope(roleId, targetTenantId);
        Criteria criteria = activeCriteria(Criteria.of()
                .eq("roleId", role.getId())
                .eq("enabled", Boolean.TRUE)
                .eq("managementScopeType", scope.managementScopeType()));
        if (scope.managementScopeId() == null) {
            criteria.isNull("managementScopeId");
        } else {
            criteria.eq("managementScopeId", scope.managementScopeId());
        }
        return accountRoleGrantDao.query(criteria, ALL);
    }

    public List<String> userIds(String roleId) {
        return accountRoleGrants(roleId).stream()
                .map(AccountRoleGrant::getUserId)
                .distinct()
                .toList();
    }

    public String grantEmploymentRole(String roleId, String employeePositionId) {
        return grantEmploymentRoleResult(roleId, employeePositionId).grantId();
    }

    public RoleGrantMutationResult grantEmploymentRoleResult(String roleId, String employeePositionId) {
        return grantEmploymentRoleIfAbsent(roleId, employeePositionId);
    }

    public int revokeEmploymentRole(String roleId, String employeePositionId) {
        Role role = requireBindableRole(roleId);
        requireEmploymentAssignableRole(role);
        requireSystemManagedMutationAllowed(role, "revoke employment role");
        EmploymentRoleGrant grant = findEmploymentRoleGrant(role.getId(), employeePositionId);
        return grant == null ? 0 : employmentRoleGrantDao.deleteById(grant.getId());
    }

    public int deleteEmploymentRoleGrant(String roleId, String grantId) {
        Role role = requireEnabledRole(roleId);
        requireSystemManagedMutationAllowed(role, "delete employment role grant");
        EmploymentRoleGrant grant = employmentRoleGrantDao.query(activeCriteria(Criteria.of()
                        .eq("id", Preconditions.requireText(grantId, "grantId"))),
                new PageRequest(0, 1)).stream().findFirst().orElse(null);
        if (grant == null || !SortAbility.sameValue(role.getId(), grant.getRoleId())) {
            throw BusinessExceptions.warning("iam.role.employment-grant-role-mismatch",
                    "该任职角色绑定不属于当前角色");
        }
        return employmentRoleGrantDao.deleteById(grant.getId());
    }

    public List<EmploymentRoleGrant> employmentRoleGrants(String roleId) {
        Role role = requireEnabledRole(roleId);
        requireEmploymentAssignableRole(role);
        return employmentRoleGrantDao.query(activeCriteria(Criteria.of()
                        .eq("roleId", role.getId())
                        .eq("enabled", Boolean.TRUE)), ALL);
    }

    public int grantAction(String roleId, String moduleAlias, String actionCode) {
        return grantAction(roleId, moduleAlias, actionCode, null,
                TenantScopePolicy.CURRENT_TENANT, null, null, null);
    }

    public int grantAction(String roleId,
                           String moduleAlias,
                           String actionCode,
                           DataScopePolicy dataScopePolicy,
                           TenantScopePolicy tenantScopePolicy) {
        return grantAction(roleId, moduleAlias, actionCode, dataScopePolicy, tenantScopePolicy, null, null, null);
    }

    public int grantAction(String roleId,
                           String moduleAlias,
                           String actionCode,
                           DataScopePolicy dataScopePolicy,
                           TenantScopePolicy tenantScopePolicy,
                           String scopeCondition,
                           String referenceFieldId,
                           String referenceActionCode) {
        Role role = requireConfigurableRole(roleId);
        requireSystemManagedMutationAllowed(role, "grant action");

        String validModuleAlias = requireModuleAlias(moduleAlias);
        requireTenantApplicationOpenedForRole(role, validModuleAlias);
        String requestedActionCode = requireActionCode(actionCode);
        String validActionCode = resolveGrantablePermissionActionCode(validModuleAlias, requestedActionCode);
        DataScopePolicy requestedDataScopePolicy = defaultDataScopePolicy(role, validModuleAlias,
                requestedActionCode, dataScopePolicy);
        DataScopePolicy validDataScopePolicy = normalizeDataScopePolicy(role, validModuleAlias, requestedDataScopePolicy,
                scopeCondition, referenceFieldId, referenceActionCode);

        RoleAction roleAction = findRoleAction(roleId, validModuleAlias, validActionCode);
        boolean exists = roleAction != null;
        if (!exists) {
            roleAction = new RoleAction();
            roleAction.setRoleId(roleId);
            roleAction.setModuleAlias(validModuleAlias);
            roleAction.setActionCode(validActionCode);
        }
        roleAction.setDataScopePolicy(validDataScopePolicy);
        roleAction.setTenantScopePolicy(normalizeTenantScopePolicy(tenantScopePolicy));
        roleAction.setScopeCondition(normalizeBlank(scopeCondition));
        roleAction.setReferenceFieldId(validDataScopePolicy == DataScopePolicy.REFERENCE_DEPENDENCY
                ? normalizeBlank(referenceFieldId) : null);
        roleAction.setReferenceActionCode(validDataScopePolicy == DataScopePolicy.REFERENCE_DEPENDENCY
                ? normalizeReferenceActionCode(referenceActionCode) : null);
        roleAction.setEnabled(true);

        if (exists) {
            prepareChildUpdate(roleAction);
            return roleActionDao.updateById(roleAction);
        }
        prepareRoleActionInsert(role, roleAction);
        roleActionDao.insert(roleAction);
        return 1;
    }

    public int grantActions(String roleId, List<ActionGrantCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (ActionGrantCommand command : commands.stream().filter(Objects::nonNull).toList()) {
            changed += grantAction(
                    roleId,
                    command.moduleAlias(),
                    command.actionCode(),
                    command.dataScopePolicy(),
                    command.tenantScopePolicy(),
                    command.scopeCondition(),
                    command.referenceFieldId(),
                    command.referenceActionCode()
            );
        }
        return changed;
    }

    public int revokeAction(String roleId, String moduleAlias, String actionCode) {
        Role role = requireEnabledRole(roleId);
        requireSystemManagedMutationAllowed(role, "revoke action");
        String validModuleAlias = requireModuleAlias(moduleAlias);
        String validActionCode = resolveGrantablePermissionActionCode(validModuleAlias, actionCode);
        RoleAction roleAction = findRoleAction(roleId, validModuleAlias, validActionCode);
        if (roleAction == null) {
            return 0;
        }
        roleAction.setEnabled(false);
        prepareChildUpdate(roleAction);
        return roleActionDao.updateById(roleAction);
    }

    public int revokeActions(String roleId, List<ActionRevokeCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (ActionRevokeCommand command : commands.stream().filter(Objects::nonNull).toList()) {
            changed += revokeAction(roleId, command.moduleAlias(), command.actionCode());
        }
        return changed;
    }

    /**
     * Applies one complete permission-matrix draft in a single transaction.
     *
     * <p>The web layer submits every visible action of the edited modules. Keeping
     * grant and revoke decisions in one service operation prevents a partially
     * applied matrix when one action fails validation.</p>
     */
    @Transactional
    public int replacePermissionActions(String roleId, List<PermissionActionCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        Map<String, PermissionActionCommand> commandsByAction = new LinkedHashMap<>();
        for (PermissionActionCommand command : commands.stream().filter(Objects::nonNull).toList()) {
            String key = actionKey(command.moduleAlias(), command.actionCode());
            if (commandsByAction.putIfAbsent(key, command) != null) {
                throw new IllegalArgumentException("duplicate permission action: " + key);
            }
        }
        int changed = 0;
        for (PermissionActionCommand command : commandsByAction.values()) {
            if (command.granted()) {
                changed += grantAction(
                        roleId,
                        command.moduleAlias(),
                        command.actionCode(),
                        command.dataScopePolicy(),
                        command.tenantScopePolicy(),
                        command.scopeCondition(),
                        command.referenceFieldId(),
                        command.referenceActionCode()
                );
                continue;
            }
            changed += revokeAction(roleId, command.moduleAlias(), command.actionCode());
        }
        return changed;
    }

    private void requireTenantApplicationOpenedForRole(Role role, String moduleAlias) {
        if (tenantApplicationService == null || role.getOwnerScopeType() == RoleOwnerScopeType.PLATFORM) {
            return;
        }
        tenantApplicationService.requireApplicationOpened(
                Preconditions.requireText(role.getTenantId(), "role.tenantId"),
                PlatformNameRules.applicationAliasOfModuleAlias(moduleAlias));
    }

    public boolean hasActionPermission(String userId, String moduleAlias, String actionCode) {
        return !effectiveActionGrants(userId, moduleAlias, actionCode).isEmpty();
    }

    public boolean hasActionPermission(BusinessPrincipal principal, String moduleAlias, String actionCode) {
        return !effectiveActionGrants(principal, moduleAlias, actionCode).isEmpty();
    }

    /**
     * Returns whether the account has the platform-managed tenant-administrator authority in the
     * supplied tenant. Role grants remain the source of truth for assignment and revocation.
     */
    public boolean hasTenantAdministratorAccess(String userId, String tenantId) {
        String validUserId = Preconditions.requireText(userId, "userId");
        String validTenantId = Preconditions.requireText(tenantId, "tenantId");
        return effectiveRoleGrants(validUserId).stream()
                .filter(grant -> grant.sourceType() == RoleAssignmentType.ACCOUNT)
                .filter(grant -> grant.managementScopeType() == ManagementScopeType.TENANT)
                .filter(grant -> validTenantId.equals(grant.managementScopeId()))
                .map(EffectiveRoleGrant::roleId)
                .map(this::selectGrantedRole)
                .anyMatch(role -> role != null
                        && role.getSystemPurpose() == RoleSystemPurpose.TENANT_ADMIN
                        && role.getOwnerScopeType() == RoleOwnerScopeType.TENANT
                        && validTenantId.equals(role.getTenantId())
                        && validTenantId.equals(role.getOwnerScopeId()));
    }

    public List<RoleAction> effectiveActionGrants(String userId, String moduleAlias, String actionCode) {
        return effectiveActionGrantsWithContext(userId, moduleAlias, actionCode).stream()
                .map(EffectiveRoleActionGrant::actionGrant)
                .distinct()
                .toList();
    }

    public List<RoleAction> effectiveActionGrants(BusinessPrincipal principal, String moduleAlias, String actionCode) {
        return effectiveActionGrantsWithContext(principal, moduleAlias, actionCode).stream()
                .map(EffectiveRoleActionGrant::actionGrant)
                .distinct()
                .toList();
    }

    public List<EffectiveRoleActionGrant> effectiveActionGrantsWithContext(String userId,
                                                                           String moduleAlias,
                                                                           String actionCode) {
        return effectiveActionGrantsWithContext(effectiveRoleGrants(userId), moduleAlias, actionCode);
    }

    public List<EffectiveRoleActionGrant> effectiveActionGrantsWithContext(BusinessPrincipal principal,
                                                                           String moduleAlias,
                                                                           String actionCode) {
        return effectiveActionGrantsWithContext(effectiveRoleGrants(principal), moduleAlias, actionCode);
    }

    private List<EffectiveRoleActionGrant> effectiveActionGrantsWithContext(List<EffectiveRoleGrant> roleGrants,
                                                                            String moduleAlias,
                                                                            String actionCode) {
        if (roleGrants.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> roleIds = new LinkedHashSet<>();
        roleGrants.stream()
                .filter(grant -> grant.sourceType() != RoleAssignmentType.EMPLOYMENT
                        || !dataGrantRole(grant.roleId()))
                .map(EffectiveRoleGrant::roleId)
                .forEach(roleIds::add);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        String permissionActionCode = permissionActionCode(actionCode);
        List<RoleAction> actionGrants = roleActionDao.query(Criteria.of()
                        .in("roleId", List.copyOf(roleIds))
                        .eq("moduleAlias", requireModuleAlias(moduleAlias))
                        .eq("actionCode", permissionActionCode)
                        .eq("enabled", Boolean.TRUE),
                ALL);
        if (actionGrants.isEmpty()) {
            return List.of();
        }
        Map<String, List<EffectiveRoleGrant>> grantsByRoleId = roleGrantsByRoleId(roleGrants);
        ArrayList<EffectiveRoleActionGrant> effective = new ArrayList<>();
        for (RoleAction actionGrant : actionGrants) {
            if (dataGrantRole(actionGrant.getRoleId())) {
                continue;
            }
            List<EffectiveRoleGrant> matchedRoleGrants = grantsByRoleId.get(actionGrant.getRoleId());
            if (matchedRoleGrants == null || matchedRoleGrants.isEmpty()) {
                continue;
            }
            matchedRoleGrants.forEach(roleGrant -> effective.add(new EffectiveRoleActionGrant(actionGrant, roleGrant)));
        }
        return List.copyOf(effective);
    }

    public RoleAction inheritedDataGrantAction(EffectiveRoleGrant roleGrant, String moduleAlias, String actionCode) {
        if (roleGrant == null || roleGrant.employeePositionId() == null) {
            return null;
        }
        requireModuleAlias(moduleAlias);
        List<String> dataGrantRoleIds = effectiveDataGrantRoleIds(roleGrant.employeePositionId(), null);
        if (dataGrantRoleIds.isEmpty()) {
            return null;
        }
        // Legacy constructor wiring is retained for focused contract tests and older embedders.
        // Runtime beans always receive the dedicated template store.
        if (roleDataGrantActionDao == null) {
            return inheritedDataGrantActionFromLegacyRoleAction(dataGrantRoleIds, moduleAlias, actionCode,
                    roleGrant.employeePositionId());
        }
        List<RoleDataGrantAction> grants = roleDataGrantActionDao.query(Criteria.of()
                        .in("roleId", dataGrantRoleIds)
                        .eq("actionCode", permissionActionCode(actionCode))
                        .eq("enabled", Boolean.TRUE),
                ALL);
        if (grants.size() > 1) {
            throw new PlatformException("employment has more than one inherited data grant action: "
                    + roleGrant.employeePositionId());
        }
        return grants.stream().findFirst().map(this::asInheritedDataGrantAction).orElse(null);
    }

    private RoleAction inheritedDataGrantActionFromLegacyRoleAction(List<String> dataGrantRoleIds,
                                                                      String moduleAlias,
                                                                      String actionCode,
                                                                      String employeePositionId) {
        List<RoleAction> grants = roleActionDao.query(Criteria.of()
                        .in("roleId", dataGrantRoleIds)
                        .eq("moduleAlias", moduleAlias)
                        .eq("actionCode", permissionActionCode(actionCode))
                        .eq("enabled", Boolean.TRUE),
                ALL);
        if (grants.size() > 1) {
            throw new PlatformException("employment has more than one inherited data grant action: " + employeePositionId);
        }
        return grants.stream().findFirst().orElse(null);
    }

    public RoleDataGrantActionMatrix dataGrantActionMatrix(String roleId) {
        Role role = requireDataGrantRole(roleId);
        Map<String, RoleDataGrantAction> configuredByAction = dataGrantActionTemplates(role.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(RoleDataGrantAction::getActionCode,
                        action -> action, (left, right) -> left, LinkedHashMap::new));
        List<RoleDataGrantActionMatrix.Action> actions = standardDataGrantActions().stream()
                .map(action -> {
                    RoleDataGrantAction configured = configuredByAction.get(action.permissionActionCode());
                    return new RoleDataGrantActionMatrix.Action(
                            action.permissionActionCode(), action.title(), Boolean.TRUE.equals(configured == null ? null : configured.getEnabled()),
                            configured == null ? null : configured.getDataScopePolicy());
                })
                .toList();
        return new RoleDataGrantActionMatrix(role.getId(), actions);
    }

    @Transactional
    public int replaceDataGrantActions(String roleId, List<DataGrantActionCommand> commands) {
        Role role = requireDataGrantRole(roleId);
        requireSystemManagedMutationAllowed(role, "replace data grant actions");
        if (roleDataGrantActionDao == null) {
            throw new IllegalStateException("role data grant action storage is not available");
        }
        LinkedHashMap<String, DataScopePolicy> desired = new LinkedHashMap<>();
        for (DataGrantActionCommand command : commands == null ? List.<DataGrantActionCommand>of() : commands) {
            if (command == null || command.enabled() == false) {
                continue;
            }
            String actionCode = requireStandardDataGrantAction(command.actionCode());
            desired.put(actionCode, requireConcreteDataGrantScope(command.dataScopePolicy()));
        }
        Map<String, RoleDataGrantAction> existingByAction = dataGrantActionTemplates(role.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(RoleDataGrantAction::getActionCode,
                        action -> action, (left, right) -> left, LinkedHashMap::new));
        int changed = 0;
        for (RoleDataGrantAction existing : existingByAction.values()) {
            if (desired.containsKey(existing.getActionCode()) || !Boolean.TRUE.equals(existing.getEnabled())) {
                continue;
            }
            existing.setEnabled(false);
            prepareChildUpdate(existing);
            changed += roleDataGrantActionDao.updateById(existing);
        }
        for (Map.Entry<String, DataScopePolicy> entry : desired.entrySet()) {
            RoleDataGrantAction action = existingByAction.get(entry.getKey());
            if (action == null) {
                action = new RoleDataGrantAction();
                action.setRoleId(role.getId());
                action.setActionCode(entry.getKey());
                action.setDataScopePolicy(entry.getValue());
                action.setEnabled(true);
                prepareRoleActionInsert(role, action);
                roleDataGrantActionDao.insert(action);
                changed++;
                continue;
            }
            if (Boolean.TRUE.equals(action.getEnabled()) && action.getDataScopePolicy() == entry.getValue()) {
                continue;
            }
            action.setDataScopePolicy(entry.getValue());
            action.setEnabled(true);
            prepareChildUpdate(action);
            changed += roleDataGrantActionDao.updateById(action);
        }
        return changed;
    }

    public Set<String> effectiveRoleIds(String userId) {
        LinkedHashSet<String> effective = new LinkedHashSet<>();
        effectiveRoleGrants(userId).stream()
                .map(EffectiveRoleGrant::roleId)
                .forEach(effective::add);
        return effective;
    }

    public Set<String> effectiveRoleIds(BusinessPrincipal principal) {
        LinkedHashSet<String> effective = new LinkedHashSet<>();
        effectiveRoleGrants(principal).stream()
                .map(EffectiveRoleGrant::roleId)
                .forEach(effective::add);
        return effective;
    }

    public List<EffectiveRoleGrant> effectiveRoleGrants(String userId) {
        String validUserId = Preconditions.requireText(userId, "userId");
        ArrayList<EffectiveRoleGrant> effective = new ArrayList<>();
        accountRoleGrantsForUser(validUserId).forEach(grant -> appendAccountRoleGrant(effective, grant));

        String employeeId = employeeAccountService == null ? null : employeeAccountService.employeeIdOfUser(validUserId);
        if (employeeId == null || employeeId.isBlank() || employeeService == null || employeePositionService == null) {
            return List.copyOf(effective);
        }
        Employee employee = employeeService.selectActiveRaw(employeeId);
        if (employee == null || !Boolean.TRUE.equals(employee.getEnabled())) {
            return List.copyOf(effective);
        }
        for (EmployeePosition position : employeePositionService.activePositionsForRoleResolution(employee.getId())) {
            if (position == null || !Boolean.TRUE.equals(position.getEnabled())) {
                continue;
            }
            effectiveEmploymentRoleGrants(position.getId())
                    .forEach(grant -> appendEmploymentRoleGrant(effective, grant, position));
        }
        return List.copyOf(effective);
    }

    public List<EffectiveRoleGrant> effectiveRoleGrants(BusinessPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        ArrayList<EffectiveRoleGrant> effective = new ArrayList<>();
        if (principal.userId() != null) {
            accountRoleGrantsForUser(principal.userId()).forEach(grant -> appendAccountRoleGrant(effective, grant));
        }
        if (principal.employeePositionId() != null) {
            EmployeePosition position = employeePositionService == null
                    ? null
                    : employeePositionService.selectActiveRaw(principal.employeePositionId());
            if (isActivePrincipalPosition(principal, position)) {
                effectiveEmploymentRoleGrants(principal.employeePositionId())
                        .forEach(grant -> appendEmploymentRoleGrant(effective, grant, position));
            }
        }
        return List.copyOf(effective);
    }

    public List<RoleAction> alignedActions(String roleId, List<String> moduleAliases, List<String> actionCodes) {
        Preconditions.requireText(roleId, "roleId");
        if (moduleAliases == null || moduleAliases.isEmpty() || actionCodes == null || actionCodes.isEmpty()) {
            return List.of();
        }
        List<RoleAction> configured = roleActionDao.query(activeCriteria(Criteria.of()
                        .eq("roleId", roleId)
                        .in("moduleAlias", moduleAliases)
                        .in("actionCode", actionCodes)),
                ALL,
                Sort.asc("moduleAlias"),
                Sort.asc("actionCode"));
        return moduleAliases.stream()
                .flatMap(moduleAlias -> actionCodes.stream().map(actionCode ->
                        configured.stream()
                                .filter(item -> moduleAlias.equals(item.getModuleAlias())
                                        && actionCode.equals(item.getActionCode()))
                                .findFirst()
                                .orElseGet(() -> disabledActionView(roleId, moduleAlias, actionCode))))
                .toList();
    }

    public RolePermissionMatrix permissionMatrix(String roleId, List<GrantableAction> actions) {
        Role role = requireConfigurableRole(roleId);
        String validRoleId = role.getId();
        if (actions == null || actions.isEmpty()) {
            return new RolePermissionMatrix(validRoleId, List.of());
        }
        LinkedHashMap<String, GrantableAction> actionByKey = new LinkedHashMap<>();
        for (GrantableAction action : actions) {
            if (action == null) {
                continue;
            }
            String key = actionKey(action.moduleAlias(), action.permissionActionCode());
            GrantableAction existing = actionByKey.get(key);
            if (existing == null || action.actionCode().equals(action.permissionActionCode())) {
                actionByKey.put(key, action);
            }
        }
        if (actionByKey.isEmpty()) {
            return new RolePermissionMatrix(validRoleId, List.of());
        }

        List<String> moduleAliases = actionByKey.values().stream()
                .map(GrantableAction::moduleAlias)
                .distinct()
                .toList();
        List<String> actionCodes = actionByKey.values().stream()
                .map(GrantableAction::permissionActionCode)
                .distinct()
                .toList();
        Map<String, RoleAction> configuredByKey = new LinkedHashMap<>();
        roleActionDao.query(activeCriteria(Criteria.of()
                                .eq("roleId", validRoleId)
                                .in("moduleAlias", moduleAliases)
                                .in("actionCode", actionCodes)),
                        ALL,
                        Sort.asc("moduleAlias"),
                        Sort.asc("actionCode"))
                .forEach(action -> configuredByKey.put(actionKey(action.getModuleAlias(), action.getActionCode()), action));

        LinkedHashMap<String, List<RolePermissionAction>> actionsByModule = new LinkedHashMap<>();
        actionByKey.values().forEach(action -> actionsByModule
                .computeIfAbsent(action.moduleAlias(), ignored -> new ArrayList<>())
                .add(RolePermissionAction.of(action,
                        configuredByKey.get(actionKey(action.moduleAlias(), action.permissionActionCode())))));
        List<RolePermissionMatrix.Module> modules = actionsByModule.entrySet().stream()
                .map(entry -> new RolePermissionMatrix.Module(entry.getKey(), entry.getValue()))
                .toList();
        return new RolePermissionMatrix(validRoleId, modules);
    }

    @Override
    public void afterDelete(String id, Role role, int deleted) {
        accountRoleGrantDao.query(activeCriteria(Criteria.of().eq("roleId", id)), ALL)
                .forEach(binding -> accountRoleGrantDao.deleteById(binding.getId()));
        employmentRoleGrantDao.query(activeCriteria(Criteria.of().eq("roleId", id)), ALL)
                .forEach(binding -> employmentRoleGrantDao.deleteById(binding.getId()));
        roleActionDao.query(activeCriteria(Criteria.of().eq("roleId", id)), ALL)
                .forEach(action -> roleActionDao.deleteById(action.getId()));
        if (roleDataGrantActionDao != null) {
            roleDataGrantActionDao.query(activeCriteria(Criteria.of().eq("roleId", id)), ALL)
                    .forEach(action -> roleDataGrantActionDao.deleteById(action.getId()));
        }
        removeRoleFromGroups(id);
    }

    private Role requireEnabledRole(String roleId) {
        Role role = requireEnabled(Preconditions.requireText(roleId, "roleId"), "role is not active: " + roleId);
        normalizeLoadedRoleDefaults(role);
        return role;
    }

    private Role requireBindableRole(String roleId) {
        String validRoleId = Preconditions.requireText(roleId, "roleId");
        Role role = select(validRoleId);
        if (role == null && TenantContext.currentTenantId().isPresent()) {
            role = selectPlatformSharedRole(validRoleId);
        }
        if (role == null || !Boolean.TRUE.equals(role.getEnabled())) {
            throw BusinessExceptions.warning("iam.role.inactive", "角色不存在或已停用");
        }
        normalizeLoadedRoleDefaults(role);
        if (role.getOwnerScopeType() == RoleOwnerScopeType.PLATFORM
                && role.getSharePolicy() != RoleSharePolicy.PLATFORM
                && TenantContext.currentTenantId().isPresent()) {
            throw BusinessExceptions.warning("iam.role.platform-private-not-bindable",
                    "租户不能绑定平台私有角色");
        }
        return role;
    }

    private Role selectPlatformSharedRole(String roleId) {
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve platform shared role")) {
            return getDao().query(activeCriteria(Criteria.of()
                            .eq("id", Preconditions.requireText(roleId, "roleId"))
                            .eq("ownerScopeType", RoleOwnerScopeType.PLATFORM)
                            .eq("sharePolicy", RoleSharePolicy.PLATFORM)),
                    new PageRequest(0, 1)).stream().findFirst().orElse(null);
        }
    }

    private Role selectGrantedRole(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        Role role = select(roleId);
        if (role == null && TenantContext.currentTenantId().isPresent()) {
            role = selectPlatformSharedRole(roleId);
        }
        if (role != null) {
            normalizeLoadedRoleDefaults(role);
        }
        return role;
    }

    private void normalizeLoadedRoleDefaults(Role role) {
        if (role.getSystemPurpose() == null) {
            role.setSystemPurpose(RoleSystemPurpose.NONE);
        }
        if (role.getAssignmentType() == null) {
            role.setAssignmentType(RoleAssignmentType.EMPLOYMENT);
        }
        if (role.getRoleKind() == null) {
            role.setRoleKind(RoleKind.STANDARD);
        }
        if (role.getOwnerScopeType() == null) {
            role.setOwnerScopeType(RoleOwnerScopeType.TENANT);
        }
        if (role.getSharePolicy() == null) {
            role.setSharePolicy(RoleSharePolicy.PRIVATE);
        }
        if (role.getOwnerScopeKey() == null || role.getOwnerScopeKey().isBlank()) {
            role.setOwnerScopeKey(ownerScopeKey(role.getOwnerScopeType(), role.getOwnerScopeId()));
        }
    }

    private Role requireConfigurableRole(String roleId) {
        Role role = requireEnabledRole(roleId);
        if (role.getRoleKind() == RoleKind.GROUP) {
            throw BusinessExceptions.warning("iam.role.group-action-grant-denied",
                    "角色组不能直接配置动作授权");
        }
        return role;
    }

    private void requireAccountRole(Role role) {
        if (role.getAssignmentType() != RoleAssignmentType.ACCOUNT) {
            throw BusinessExceptions.warning("iam.role.not-account-role", "当前角色不是账号角色");
        }
        if (role.getRoleKind() == RoleKind.GROUP || role.getRoleKind() == RoleKind.DATA_GRANT) {
            throw BusinessExceptions.warning("iam.role.account-grant-kind-denied",
                    "角色组和数据授权角色不能绑定到账号");
        }
    }

    private void requireEmploymentAssignableRole(Role role) {
        if (role.getAssignmentType() != RoleAssignmentType.EMPLOYMENT) {
            throw BusinessExceptions.warning("iam.role.not-employment-role",
                    "当前角色不是任职角色");
        }
    }

    private void requireSystemManagedMutationAllowed(Role role, String operation) {
        if (role != null && Boolean.TRUE.equals(role.getSystemManaged()) && !CurrentUserContext.isSystem()) {
            throw BusinessExceptions.warning("iam.role.system-managed-mutation-denied",
                    "系统托管角色不能在当前上下文中修改");
        }
    }

    private void requireStructuralFieldsUnchanged(Role existing, Role updated) {
        if (existing == null || updated == null) {
            return;
        }
        if (updated.getAssignmentType() != null && existing.getAssignmentType() != updated.getAssignmentType()) {
            throw BusinessExceptions.warning("iam.role.assignment-type-immutable",
                    "角色创建后不能修改分配类型");
        }
        if (updated.getRoleKind() != null && existing.getRoleKind() != updated.getRoleKind()) {
            throw BusinessExceptions.warning("iam.role.kind-immutable", "角色创建后不能修改角色类型");
        }
        if (updated.getSystemPurpose() != null && existing.getSystemPurpose() != updated.getSystemPurpose()) {
            throw BusinessExceptions.warning("iam.role.system-purpose-immutable", "角色创建后不能修改系统用途");
        }
        if (updated.getOwnerScopeType() != null && existing.getOwnerScopeType() != updated.getOwnerScopeType()) {
            throw BusinessExceptions.warning("iam.role.owner-scope-type-immutable",
                    "角色创建后不能修改归属范围类型");
        }
        if (updated.getOwnerScopeId() != null && !Objects.equals(existing.getOwnerScopeId(), updated.getOwnerScopeId())) {
            throw BusinessExceptions.warning("iam.role.owner-scope-id-immutable",
                    "角色创建后不能修改归属范围");
        }
    }

    private void normalizeOwnerAndSharePolicy(Role role) {
        if (role.getOwnerScopeType() == null) {
            role.setOwnerScopeType(defaultOwnerScopeType());
        }
        role.setOwnerScopeId(normalizeOwnerScopeId(role.getOwnerScopeType(), role.getOwnerScopeId()));
        role.setOwnerScopeKey(ownerScopeKey(role.getOwnerScopeType(), role.getOwnerScopeId()));
        if (role.getSharePolicy() == null) {
            role.setSharePolicy(RoleSharePolicy.PRIVATE);
        }
        validateSharePolicy(role.getOwnerScopeType(), role.getSharePolicy(), role.getId());
        validateOwnerScope(role);
    }

    private Role tenantAdminRole(String roleId, String title, String description) {
        Role role = new Role();
        role.setId(roleId);
        role.setAssignmentType(RoleAssignmentType.ACCOUNT);
        role.setRoleKind(RoleKind.STANDARD);
        role.setTitle(title);
        role.setOwnerScopeType(RoleOwnerScopeType.TENANT);
        role.setOwnerScopeId(TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException("tenant admin role requires tenant context")));
        role.setSharePolicy(RoleSharePolicy.TENANT);
        role.setBuiltIn(Boolean.TRUE);
        role.setSystemManaged(Boolean.TRUE);
        role.setSystemPurpose(RoleSystemPurpose.TENANT_ADMIN);
        role.setDescription(description);
        role.setEnabled(Boolean.TRUE);
        role.setSortOrder(1);
        return role;
    }

    private Role organizationAdminRole(String roleId, String organizationId, String title, String description) {
        Role role = new Role();
        role.setId(roleId);
        role.setAssignmentType(RoleAssignmentType.ACCOUNT);
        role.setRoleKind(RoleKind.STANDARD);
        role.setTitle(title);
        role.setOwnerScopeType(RoleOwnerScopeType.ORGANIZATION);
        role.setOwnerScopeId(Preconditions.requireText(organizationId, "organizationId"));
        role.setSharePolicy(RoleSharePolicy.OWNER_AND_CHILDREN);
        role.setBuiltIn(Boolean.TRUE);
        role.setSystemManaged(Boolean.TRUE);
        role.setSystemPurpose(RoleSystemPurpose.ORGANIZATION_ADMIN);
        role.setDescription(description);
        role.setEnabled(Boolean.TRUE);
        role.setSortOrder(1);
        return role;
    }

    private Role repairSystemManagedTenantAdminRole(Role role, String title, String description) {
        Role desired = tenantAdminRole(role.getId(), title, description);
        return repairSystemManagedAdminRole(role, desired, description);
    }

    private Role repairSystemManagedOrganizationAdminRole(Role role,
                                                          String organizationId,
                                                          String title,
                                                          String description) {
        Role desired = organizationAdminRole(role.getId(), organizationId, title, description);
        return repairSystemManagedAdminRole(role, desired, description);
    }

    private Role repairSystemManagedAdminRole(Role role, Role desired, String description) {
        requireRepairableSystemManagedAdminRole(role, desired);
        boolean changed = false;
        String currentTenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException("admin role repair requires tenant context"));
        changed |= setIfChanged(role::getTenantId, role::setTenantId, currentTenantId);
        changed |= setIfChanged(role::getAssignmentType, role::setAssignmentType, desired.getAssignmentType());
        changed |= setIfChanged(role::getRoleKind, role::setRoleKind, desired.getRoleKind());
        changed |= setIfChanged(role::getTitle, role::setTitle, desired.getTitle());
        changed |= setIfChanged(role::getOwnerScopeType, role::setOwnerScopeType, desired.getOwnerScopeType());
        changed |= setIfChanged(role::getOwnerScopeId, role::setOwnerScopeId, desired.getOwnerScopeId());
        changed |= setIfChanged(role::getOwnerScopeKey, role::setOwnerScopeKey,
                ownerScopeKey(desired.getOwnerScopeType(), desired.getOwnerScopeId()));
        changed |= setIfChanged(role::getSharePolicy, role::setSharePolicy, desired.getSharePolicy());
        changed |= setIfChanged(role::getBuiltIn, role::setBuiltIn, Boolean.TRUE);
        changed |= setIfChanged(role::getSystemManaged, role::setSystemManaged, Boolean.TRUE);
        changed |= setIfChanged(role::getSystemPurpose, role::setSystemPurpose, desired.getSystemPurpose());
        changed |= setIfChanged(role::getEnabled, role::setEnabled, Boolean.TRUE);
        changed |= setIfChanged(role::getDeleted, role::setDeleted, Boolean.FALSE);
        changed |= setIfChanged(role::getDeletedAt, role::setDeletedAt, null);
        if (role.getSortOrder() == null) {
            role.setSortOrder(1);
            changed = true;
        }
        changed |= setIfChanged(role::getDescription, role::setDescription, description);
        if (!changed) {
            return role;
        }
        Integer expectedVersion = role.getVersion();
        EntityLifecycle.prepareUpdate(role, Instant.now(), EntityLifecycle.nextVersion(expectedVersion));
        int updated = expectedVersion == null
                ? getDao().updateById(role)
                : getDao().updateByIdAndVersion(role, expectedVersion);
        if (updated <= 0) {
            throw new PlatformException("Failed to repair admin role: " + role.getId());
        }
        afterChanged(role);
        return role;
    }

    private void requireRepairableSystemManagedAdminRole(Role role, Role desired) {
        if (role == null || desired == null) {
            throw new PlatformException("admin role repair requires role");
        }
        if (!Boolean.TRUE.equals(role.getSystemManaged()) || !Boolean.TRUE.equals(role.getBuiltIn())) {
            throw new PlatformException("admin role id is already used by non system managed role: " + role.getId());
        }
        String currentTenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException("admin role repair requires tenant context"));
        if (role.getTenantId() != null && !role.getTenantId().isBlank()
                && !Objects.equals(role.getTenantId(), currentTenantId)) {
            throw new PlatformException("admin role tenant mismatch: " + role.getId());
        }
        if (role.getRoleKind() != null && role.getRoleKind() != desired.getRoleKind()) {
            throw new PlatformException("admin role kind mismatch: " + role.getId());
        }
        if (role.getOwnerScopeType() != null && role.getOwnerScopeType() != desired.getOwnerScopeType()) {
            throw new PlatformException("admin role owner scope type mismatch: " + role.getId());
        }
        if (role.getOwnerScopeId() != null && !role.getOwnerScopeId().isBlank()
                && !Objects.equals(role.getOwnerScopeId(), desired.getOwnerScopeId())) {
            throw new PlatformException("admin role owner scope id mismatch: " + role.getId());
        }
    }

    private <V> boolean setIfChanged(java.util.function.Supplier<V> getter,
                                     java.util.function.Consumer<V> setter,
                                     V desired) {
        if (Objects.equals(getter.get(), desired)) {
            return false;
        }
        setter.accept(desired);
        return true;
    }

    private RoleOwnerScopeType defaultOwnerScopeType() {
        return TenantContext.isSystem() ? RoleOwnerScopeType.PLATFORM : RoleOwnerScopeType.TENANT;
    }

    private void requirePlatformRoleSystemContext() {
        if (!TenantContext.isSystem()) {
            throw BusinessExceptions.warning("iam.role.platform-management-system-context-required",
                    "仅系统身份可以管理平台角色");
        }
    }

    private String normalizeOwnerScopeId(RoleOwnerScopeType ownerScopeType, String ownerScopeId) {
        if (ownerScopeType == RoleOwnerScopeType.PLATFORM) {
            return null;
        }
        if (ownerScopeType == RoleOwnerScopeType.TENANT) {
            String normalized = normalizeBlank(ownerScopeId);
            String currentTenantId = TenantContext.currentTenantId()
                    .orElseThrow(() -> BusinessExceptions.warning("iam.role.tenant-context-required",
                            "租户角色管理需要租户上下文"));
            if (normalized != null && !Objects.equals(normalized, currentTenantId)) {
                throw BusinessExceptions.warning("iam.role.owner-tenant-mismatch",
                        "租户角色的归属租户必须与当前租户一致");
            }
            return currentTenantId;
        }
        return Preconditions.requireText(ownerScopeId, "ownerScopeId");
    }

    private String ownerScopeKey(RoleOwnerScopeType ownerScopeType, String ownerScopeId) {
        if (ownerScopeType == RoleOwnerScopeType.PLATFORM) {
            return "platform";
        }
        if (ownerScopeType == RoleOwnerScopeType.TENANT) {
            return "tenant:" + Preconditions.requireText(ownerScopeId, "ownerScopeId");
        }
        return "organization:" + Preconditions.requireText(ownerScopeId, "ownerScopeId");
    }

    private void validateOwnerScope(Role role) {
        if (role.getOwnerScopeType() != RoleOwnerScopeType.ORGANIZATION || organizationService == null) {
            return;
        }
        Organization organization = organizationService.requireEnabled(
                role.getOwnerScopeId(),
                "role owner organization is not active: " + role.getOwnerScopeId());
        String currentTenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> BusinessExceptions.warning("iam.role.tenant-context-required",
                        "组织角色管理需要租户上下文"));
        if (!Objects.equals(currentTenantId, organization.getTenantId())) {
            throw BusinessExceptions.warning("iam.role.owner-organization-tenant-mismatch",
                    "角色归属机构不属于当前租户");
        }
    }

    private void validateSharePolicy(RoleOwnerScopeType ownerScopeType, RoleSharePolicy sharePolicy, String roleId) {
        if (ownerScopeType == RoleOwnerScopeType.PLATFORM) {
            if (sharePolicy == RoleSharePolicy.PRIVATE || sharePolicy == RoleSharePolicy.PLATFORM) {
                return;
            }
            throw BusinessExceptions.warning("iam.role.platform-share-policy-invalid",
                    "平台角色仅支持私有或平台共享策略");
        }
        if (ownerScopeType == RoleOwnerScopeType.TENANT) {
            if (sharePolicy == RoleSharePolicy.PRIVATE || sharePolicy == RoleSharePolicy.TENANT) {
                return;
            }
            throw BusinessExceptions.warning("iam.role.tenant-share-policy-invalid",
                    "租户角色仅支持私有或租户共享策略");
        }
        if (sharePolicy == RoleSharePolicy.PRIVATE || sharePolicy == RoleSharePolicy.OWNER_AND_CHILDREN) {
            return;
        }
        throw BusinessExceptions.warning("iam.role.organization-share-policy-invalid",
                "组织角色仅支持私有或归属机构及下级共享策略");
    }

    private void validateGroupMembers(String memberRoleIds) {
        int dataGrantMembers = 0;
        for (String memberRoleId : parseRoleIds(memberRoleIds)) {
            Role member = selectGrantedRole(memberRoleId);
            if (member == null) {
                throw BusinessExceptions.warning("iam.role.group-member-not-found", "角色组包含不存在的角色");
            }
            if (member.getAssignmentType() != RoleAssignmentType.EMPLOYMENT) {
                throw BusinessExceptions.warning("iam.role.group-member-assignment-type-invalid",
                        "角色组只能包含任职角色");
            }
            if (member.getRoleKind() != RoleKind.STANDARD && member.getRoleKind() != RoleKind.DATA_GRANT) {
                throw BusinessExceptions.warning("iam.role.group-member-kind-invalid",
                        "角色组只能包含标准角色或数据授权角色");
            }
            if (!Boolean.TRUE.equals(member.getEnabled())) {
                throw BusinessExceptions.warning("iam.role.group-member-inactive", "角色组不能包含已停用的角色");
            }
            if (member.getRoleKind() == RoleKind.DATA_GRANT) {
                dataGrantMembers++;
            }
        }
        if (dataGrantMembers > 1) {
            throw BusinessExceptions.warning("iam.role.group-data-grant-member-duplicate",
                    "角色组最多只能包含一个数据授权角色");
        }
    }

    private Set<String> expandGroupRoleIds(String memberRoleIds) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String memberRoleId : parseRoleIds(memberRoleIds)) {
            Role member = selectGrantedRole(memberRoleId);
            if (member != null
                    && member.getAssignmentType() == RoleAssignmentType.EMPLOYMENT
                    && (member.getRoleKind() == RoleKind.STANDARD || member.getRoleKind() == RoleKind.DATA_GRANT)
                    && Boolean.TRUE.equals(member.getEnabled())) {
                expanded.add(member.getId());
            }
        }
        return expanded;
    }

    private RoleGrantMutationResult grantAccountRoleIfAbsent(String roleId,
                                                             String userId,
                                                             ManagementScopeType managementScopeType,
                                                             String managementScopeId) {
        Role role = requireBindableRole(roleId);
        requireAccountRole(role);
        requireSystemManagedMutationAllowed(role, "grant account role");
        String validUserId = Preconditions.requireText(userId, "userId");
        if (userAccountService != null) {
            userAccountService.requireEnabled(validUserId, "user account is not active: " + validUserId);
        }
        ManagementScopeType validScopeType = normalizeManagementScopeType(managementScopeType);
        String validScopeId = normalizeManagementScopeId(validScopeType, managementScopeId);
        AccountRoleGrant existing = findAccountRoleGrant(role.getId(), validUserId, validScopeType, validScopeId);
        if (existing != null) {
            if (!Boolean.TRUE.equals(existing.getEnabled())) {
                existing.setEnabled(true);
                prepareChildUpdate(existing);
                accountRoleGrantDao.updateById(existing);
                return new RoleGrantMutationResult(existing.getId(), true);
            }
            return new RoleGrantMutationResult(existing.getId(), false);
        }
        AccountRoleGrant grant = new AccountRoleGrant();
        grant.setRoleId(role.getId());
        grant.setUserId(validUserId);
        grant.setManagementScopeType(validScopeType);
        grant.setManagementScopeId(validScopeId);
        grant.setEnabled(true);
        prepareChildInsert(grant);
        return new RoleGrantMutationResult(accountRoleGrantDao.insert(grant), true);
    }

    private RoleGrantMutationResult grantEmploymentRoleIfAbsent(String roleId, String employeePositionId) {
        Role role = requireBindableRole(roleId);
        requireEmploymentAssignableRole(role);
        requireSystemManagedMutationAllowed(role, "grant employment role");
        String validEmployeePositionId = Preconditions.requireText(employeePositionId, "employeePositionId");
        if (employeePositionService != null) {
            EmployeePosition employeePosition = employeePositionService.requireEnabled(validEmployeePositionId,
                    "employee position is not active: " + validEmployeePositionId);
            if (employeeService != null && employeePosition != null) {
                employeeService.requireEnabled(employeePosition.getEmployeeId(),
                        "employee is not active: " + employeePosition.getEmployeeId());
            }
        }
        ensureDataGrantUnique(validEmployeePositionId, role);
        ensureInheritedDataGrantCoverage(validEmployeePositionId, role);
        EmploymentRoleGrant existing = findEmploymentRoleGrant(role.getId(), validEmployeePositionId);
        if (existing != null) {
            if (!Boolean.TRUE.equals(existing.getEnabled())) {
                existing.setEnabled(true);
                prepareChildUpdate(existing);
                employmentRoleGrantDao.updateById(existing);
                return new RoleGrantMutationResult(existing.getId(), true);
            }
            return new RoleGrantMutationResult(existing.getId(), false);
        }
        EmploymentRoleGrant grant = new EmploymentRoleGrant();
        grant.setRoleId(role.getId());
        grant.setEmployeePositionId(validEmployeePositionId);
        grant.setEnabled(true);
        prepareChildInsert(grant);
        return new RoleGrantMutationResult(employmentRoleGrantDao.insert(grant), true);
    }

    private void ensureDataGrantUnique(String employeePositionId, Role newRole) {
        if (effectiveDataGrantRoleIds(employeePositionId, newRole).size() > 1) {
            throw BusinessExceptions.warning("iam.role.data-grant-duplicate",
                    "同一任职最多只能绑定一个数据授权角色");
        }
    }

    private void ensureInheritedDataGrantCoverage(String employeePositionId, Role newRole) {
        if (!requiresInheritedDataGrant(newRole)) {
            return;
        }
        if (effectiveDataGrantRoleIds(employeePositionId, newRole).isEmpty()) {
            throw BusinessExceptions.warning("iam.role.data-grant-required",
                    "使用继承数据权限前，任职必须先绑定数据授权角色");
        }
    }

    private boolean requiresInheritedDataGrant(Role role) {
        if (role == null || role.getId() == null) {
            return false;
        }
        LinkedHashSet<String> roleIds = new LinkedHashSet<>();
        if (role.getRoleKind() == RoleKind.STANDARD) {
            roleIds.add(role.getId());
        } else if (role.getRoleKind() == RoleKind.GROUP) {
            for (String memberRoleId : parseRoleIds(role.getMemberRoleIds())) {
                Role member = selectGrantedRole(memberRoleId);
                if (member != null && member.getRoleKind() == RoleKind.STANDARD) {
                    roleIds.add(member.getId());
                }
            }
        }
        return !roleIds.isEmpty() && roleActionDao.query(activeCriteria(Criteria.of()
                        .in("roleId", List.copyOf(roleIds))
                        .eq("enabled", Boolean.TRUE)
                        .eq("dataScopePolicy", DataScopePolicy.INHERIT_DATA_GRANT)), ALL)
                .stream()
                .findAny()
                .isPresent();
    }

    private void validateGroupDataGrantUsage(Role group) {
        if (group == null || group.getId() == null || group.getId().isBlank()) {
            return;
        }
        List<EmploymentRoleGrant> grants = employmentRoleGrantDao.query(activeCriteria(Criteria.of()
                        .eq("roleId", group.getId())
                        .eq("enabled", Boolean.TRUE)), ALL);
        for (EmploymentRoleGrant grant : grants) {
            if (grant == null || grant.getEmployeePositionId() == null) {
                continue;
            }
            ensureDataGrantUnique(grant.getEmployeePositionId(), group);
        }
    }

    private List<String> effectiveDataGrantRoleIds(String employeePositionId, Role extraRole) {
        LinkedHashSet<String> roleIds = effectiveEmploymentRoleGrants(employeePositionId).stream()
                .map(EmploymentRoleGrant::getRoleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (extraRole != null && extraRole.getId() != null) {
            roleIds.add(extraRole.getId());
        }
        LinkedHashSet<String> dataGrantRoleIds = new LinkedHashSet<>();
        for (String roleId : roleIds) {
            Role role = extraRole != null && SortAbility.sameValue(extraRole.getId(), roleId)
                    ? extraRole
                    : selectGrantedRole(roleId);
            collectDataGrantRoleIds(dataGrantRoleIds, role);
        }
        return List.copyOf(dataGrantRoleIds);
    }

    private void collectDataGrantRoleIds(Set<String> dataGrantRoleIds, Role role) {
        if (role == null || !Boolean.TRUE.equals(role.getEnabled())) {
            return;
        }
        if (role.getRoleKind() == RoleKind.DATA_GRANT) {
            dataGrantRoleIds.add(role.getId());
            return;
        }
        if (role.getRoleKind() != RoleKind.GROUP) {
            return;
        }
        for (String memberRoleId : parseRoleIds(role.getMemberRoleIds())) {
            Role member = selectGrantedRole(memberRoleId);
            if (member != null && member.getRoleKind() == RoleKind.DATA_GRANT
                    && Boolean.TRUE.equals(member.getEnabled())) {
                dataGrantRoleIds.add(member.getId());
            }
        }
    }

    private List<AccountRoleGrant> accountRoleGrantsForUser(String userId) {
        return accountRoleGrantDao.query(activeCriteria(Criteria.of()
                        .eq("userId", Preconditions.requireText(userId, "userId"))
                        .eq("enabled", Boolean.TRUE)), ALL);
    }

    private List<EmploymentRoleGrant> effectiveEmploymentRoleGrants(String employeePositionId) {
        return employmentRoleGrantDao.query(activeCriteria(Criteria.of()
                        .eq("employeePositionId", Preconditions.requireText(employeePositionId, "employeePositionId"))
                        .eq("enabled", Boolean.TRUE)), ALL);
    }

    private void appendAccountRoleGrant(List<EffectiveRoleGrant> effective, AccountRoleGrant grant) {
        if (grant == null || !Boolean.TRUE.equals(grant.getEnabled())) {
            return;
        }
        Role role = selectGrantedRole(grant.getRoleId());
        if (role == null || role.getAssignmentType() != RoleAssignmentType.ACCOUNT
                || !Boolean.TRUE.equals(role.getEnabled())) {
            return;
        }
        effective.add(EffectiveRoleGrant.account(
                role.getId(),
                grant.getUserId(),
                grant.getManagementScopeType(),
                grant.getManagementScopeId()));
    }

    private void appendEmploymentRoleGrant(List<EffectiveRoleGrant> effective,
                                           EmploymentRoleGrant grant,
                                           EmployeePosition position) {
        if (grant == null || !Boolean.TRUE.equals(grant.getEnabled()) || position == null) {
            return;
        }
        Role role = selectGrantedRole(grant.getRoleId());
        if (role == null || role.getAssignmentType() != RoleAssignmentType.EMPLOYMENT
                || !Boolean.TRUE.equals(role.getEnabled())) {
            return;
        }
        effective.add(EffectiveRoleGrant.employment(
                role.getId(),
                grant.getEmployeePositionId(),
                position.getOrganizationId(),
                position.getDepartmentId()));
        if (role.getRoleKind() == RoleKind.GROUP) {
            for (String memberRoleId : expandGroupRoleIds(role.getMemberRoleIds())) {
                effective.add(EffectiveRoleGrant.employment(
                        memberRoleId,
                        grant.getEmployeePositionId(),
                        position.getOrganizationId(),
                        position.getDepartmentId()));
            }
        }
    }

    private AccountRoleGrant findAccountRoleGrant(String roleId,
                                                  String userId,
                                                  ManagementScopeType managementScopeType,
                                                  String managementScopeId) {
        Criteria criteria = activeCriteria(Criteria.of()
                .eq("roleId", Preconditions.requireText(roleId, "roleId"))
                .eq("userId", Preconditions.requireText(userId, "userId"))
                .eq("managementScopeType", normalizeManagementScopeType(managementScopeType)));
        String validScopeId = normalizeManagementScopeId(normalizeManagementScopeType(managementScopeType),
                managementScopeId);
        if (validScopeId == null) {
            criteria.isNull("managementScopeId");
        } else {
            criteria.eq("managementScopeId", validScopeId);
        }
        return accountRoleGrantDao.query(criteria, new PageRequest(0, 1)).stream().findFirst().orElse(null);
    }

    private EmploymentRoleGrant findEmploymentRoleGrant(String roleId, String employeePositionId) {
        return employmentRoleGrantDao.query(activeCriteria(Criteria.of()
                        .eq("roleId", Preconditions.requireText(roleId, "roleId"))
                        .eq("employeePositionId", Preconditions.requireText(employeePositionId, "employeePositionId"))),
                new PageRequest(0, 1)).stream().findFirst().orElse(null);
    }

    private RoleAction findRoleAction(String roleId, String moduleAlias, String actionCode) {
        return roleActionDao.query(activeCriteria(Criteria.of()
                        .eq("roleId", Preconditions.requireText(roleId, "roleId"))
                        .eq("moduleAlias", requireModuleAlias(moduleAlias))
                        .eq("actionCode", requireActionCode(actionCode))),
                new PageRequest(0, 1)).stream().findFirst().orElse(null);
    }

    private void removeRoleFromGroups(String roleId) {
        List<Role> groups = list(Criteria.of().eq("roleKind", RoleKind.GROUP), ALL);
        for (Role group : groups) {
            Set<String> memberRoleIds = parseRoleIds(group.getMemberRoleIds());
            if (!memberRoleIds.remove(roleId)) {
                continue;
            }
            group.setMemberRoleIds(String.join(",", memberRoleIds));
            update(group);
        }
    }

    private boolean dataGrantRole(String roleId) {
        Role role = selectGrantedRole(roleId);
        return role != null && role.getRoleKind() == RoleKind.DATA_GRANT;
    }

    private boolean isActivePrincipalPosition(BusinessPrincipal principal, EmployeePosition position) {
        return position != null
                && Boolean.TRUE.equals(position.getEnabled())
                && (principal.employeeId() == null || Objects.equals(principal.employeeId(), position.getEmployeeId()));
    }

    private Map<String, List<EffectiveRoleGrant>> roleGrantsByRoleId(List<EffectiveRoleGrant> roleGrants) {
        LinkedHashMap<String, List<EffectiveRoleGrant>> byRoleId = new LinkedHashMap<>();
        roleGrants.stream()
                .filter(Objects::nonNull)
                .filter(grant -> grant.roleId() != null)
                .forEach(grant -> byRoleId
                        .computeIfAbsent(grant.roleId(), ignored -> new ArrayList<>())
                        .add(grant));
        return byRoleId;
    }

    private RoleAction disabledActionView(String roleId, String moduleAlias, String actionCode) {
        RoleAction action = new RoleAction();
        action.setRoleId(roleId);
        action.setModuleAlias(moduleAlias);
        action.setActionCode(actionCode);
        action.setDataScopePolicy(DataScopePolicy.NONE);
        action.setEnabled(false);
        return action;
    }

    private String actionKey(String moduleAlias, String actionCode) {
        return moduleAlias + ":" + actionCode;
    }

    public RoleDataScopePolicyCatalog dataScopePolicyCatalog(String roleId, String moduleAlias) {
        Role role = requireConfigurableRole(roleId);
        List<RoleDataScopePolicyCatalog.Option> options = dataScopeOptions(role);
        if (role.getRoleKind() == RoleKind.GROUP || role.getAssignmentType() != RoleAssignmentType.EMPLOYMENT
                || role.getRoleKind() == RoleKind.DATA_GRANT || moduleAlias == null || moduleAlias.isBlank()) {
            return new RoleDataScopePolicyCatalog(role.getId(), options, List.of());
        }
        List<ReferenceDependencyScopeCandidate> candidates = referenceDependencyScopeCatalogResolver == null
                ? List.of()
                : referenceDependencyScopeCatalogResolver.resolveCandidates(requireModuleAlias(moduleAlias));
        if (candidates.isEmpty()) {
            options = options.stream()
                    .filter(option -> option.code() != DataScopePolicy.REFERENCE_DEPENDENCY)
                    .toList();
        }
        return new RoleDataScopePolicyCatalog(role.getId(), options, candidates.stream()
                .map(candidate -> new RoleDataScopePolicyCatalog.ReferenceDependency(
                        candidate.referenceFieldId(), candidate.title(), candidate.targetModuleAlias(),
                        candidate.targetModuleTitle(), candidate.referenceActionCode(), candidate.referenceActionTitle()))
                .toList());
    }

    private List<RoleDataScopePolicyCatalog.Option> dataScopeOptions(Role role) {
        if (role.getRoleKind() == RoleKind.GROUP) {
            return List.of();
        }
        if (role.getRoleKind() == RoleKind.DATA_GRANT) {
            return List.of(DataScopePolicy.ALL, DataScopePolicy.OWNER, DataScopePolicy.ASSIGNEE,
                            DataScopePolicy.MEMBER, DataScopePolicy.ORGANIZATION,
                            DataScopePolicy.ORGANIZATION_AND_CHILDREN, DataScopePolicy.DEPARTMENT,
                            DataScopePolicy.DEPARTMENT_AND_CHILDREN)
                    .stream().map(this::dataScopeOption).toList();
        }
        if (role.getAssignmentType() == RoleAssignmentType.ACCOUNT) {
            return List.of();
        }
        return List.of(DataScopePolicy.INHERIT_DATA_GRANT, DataScopePolicy.ALL,
                        DataScopePolicy.OWNER, DataScopePolicy.ASSIGNEE, DataScopePolicy.MEMBER,
                        DataScopePolicy.ORGANIZATION, DataScopePolicy.ORGANIZATION_AND_CHILDREN,
                        DataScopePolicy.DEPARTMENT, DataScopePolicy.DEPARTMENT_AND_CHILDREN,
                        DataScopePolicy.REFERENCE_DEPENDENCY)
                .stream().map(this::dataScopeOption).toList();
    }

    private DataScopePolicy defaultDataScopePolicy(Role role,
                                                    String moduleAlias,
                                                    String actionCode,
                                                    DataScopePolicy requestedPolicy) {
        boolean employmentDataAction = role.getRoleKind() == RoleKind.STANDARD
                && role.getAssignmentType() == RoleAssignmentType.EMPLOYMENT
                && grantVerifier.requiresDataScope(moduleAlias, actionCode);
        if (employmentDataAction) {
            if (requestedPolicy == DataScopePolicy.NONE) {
                throw BusinessExceptions.warning("iam.role.employment-data-scope-required",
                        "任职角色的数据动作必须配置继承或具体的数据范围");
            }
            return requestedPolicy == null ? DataScopePolicy.INHERIT_DATA_GRANT : requestedPolicy;
        }
        return requestedPolicy == null ? DataScopePolicy.NONE : requestedPolicy;
    }

    private RoleDataScopePolicyCatalog.Option dataScopeOption(DataScopePolicy policy) {
        return new RoleDataScopePolicyCatalog.Option(policy, policy.getTitle());
    }

    private DataScopePolicy normalizeDataScopePolicy(Role role,
                                                     String moduleAlias,
                                                     DataScopePolicy dataScopePolicy,
                                                     String scopeCondition,
                                                     String referenceFieldId,
                                                     String referenceActionCode) {
        DataScopePolicy policy = dataScopePolicy == null ? DataScopePolicy.NONE : dataScopePolicy;
        if (role.getAssignmentType() == RoleAssignmentType.ACCOUNT && policy != DataScopePolicy.NONE) {
            throw BusinessExceptions.warning("iam.role.account-role-data-scope-denied",
                    "账号角色的动作不能配置数据范围");
        }
        if (role.getRoleKind() == RoleKind.DATA_GRANT) {
            if (policy == DataScopePolicy.NONE || policy == DataScopePolicy.INHERIT_DATA_GRANT) {
                throw BusinessExceptions.warning("iam.role.data-grant-scope-required",
                        "数据授权角色必须配置具体的数据范围");
            }
        }
        if (policy == DataScopePolicy.CUSTOM) {
            throw BusinessExceptions.warning("iam.role.custom-data-scope-unsupported",
                    "暂不支持自定义数据范围");
        }
        if (policy == DataScopePolicy.REFERENCE_DEPENDENCY) {
            String validReferenceFieldId = Preconditions.requireText(referenceFieldId, "referenceFieldId");
            String validReferenceActionCode = normalizeReferenceActionCode(referenceActionCode);
            boolean supported = referenceDependencyScopeCatalogResolver != null
                    && referenceDependencyScopeCatalogResolver.resolveCandidates(moduleAlias).stream()
                    .anyMatch(candidate -> candidate.referenceFieldId().equals(validReferenceFieldId)
                            && candidate.referenceActionCode().equals(validReferenceActionCode));
            if (!supported) {
                throw BusinessExceptions.warning("iam.role.reference-dependency-unavailable",
                        "当前模块没有可用的引用依赖数据范围");
            }
        }
        return policy;
    }

    private String normalizeReferenceActionCode(String referenceActionCode) {
        String value = normalizeBlank(referenceActionCode);
        if (value == null) {
            return PlatformAction.VIEW.code();
        }
        if (!PlatformAction.VIEW.code().equals(value)) {
            throw BusinessExceptions.warning("iam.role.reference-dependency-action-unsupported",
                    "引用依赖数据范围当前仅支持查看动作");
        }
        return value;
    }

    private Role requireDataGrantRole(String roleId) {
        Role role = requireConfigurableRole(roleId);
        if (role.getRoleKind() != RoleKind.DATA_GRANT) {
            throw BusinessExceptions.warning("iam.role.not-data-grant-role",
                    "当前角色不是数据授权角色");
        }
        return role;
    }

    private List<RoleDataGrantAction> dataGrantActionTemplates(String roleId) {
        if (roleDataGrantActionDao == null) {
            return List.of();
        }
        return roleDataGrantActionDao.query(activeCriteria(Criteria.of().eq("roleId", roleId)), ALL);
    }

    private List<PlatformAction> standardDataGrantActions() {
        return java.util.Arrays.stream(PlatformAction.values())
                .filter(PlatformAction::dataAuth)
                .collect(java.util.stream.Collectors.toMap(PlatformAction::permissionActionCode,
                        action -> action, (left, right) -> left, LinkedHashMap::new))
                .values().stream().toList();
    }

    private String requireStandardDataGrantAction(String actionCode) {
        String permissionActionCode = permissionActionCode(actionCode);
        boolean supported = standardDataGrantActions().stream()
                .anyMatch(action -> action.permissionActionCode().equals(permissionActionCode));
        if (!supported) {
            throw BusinessExceptions.warning("iam.role.data-grant-action-unsupported",
                    "数据授权角色仅支持配置标准数据动作");
        }
        return permissionActionCode;
    }

    private DataScopePolicy requireConcreteDataGrantScope(DataScopePolicy policy) {
        if (policy == null || policy == DataScopePolicy.NONE || policy == DataScopePolicy.INHERIT_DATA_GRANT
                || policy == DataScopePolicy.CUSTOM || policy == DataScopePolicy.REFERENCE_DEPENDENCY) {
            throw BusinessExceptions.warning("iam.role.data-grant-scope-required",
                    "数据授权角色必须配置具体的标准数据范围");
        }
        return policy;
    }

    private RoleAction asInheritedDataGrantAction(RoleDataGrantAction action) {
        RoleAction adapted = new RoleAction();
        adapted.setRoleId(action.getRoleId());
        adapted.setActionCode(action.getActionCode());
        adapted.setDataScopePolicy(action.getDataScopePolicy());
        adapted.setEnabled(action.getEnabled());
        return adapted;
    }

    private TenantScopePolicy normalizeTenantScopePolicy(TenantScopePolicy tenantScopePolicy) {
        return tenantScopePolicy == null ? TenantScopePolicy.CURRENT_TENANT : tenantScopePolicy;
    }

    private ManagementScopeType normalizeManagementScopeType(ManagementScopeType managementScopeType) {
        return managementScopeType == null ? ManagementScopeType.TENANT : managementScopeType;
    }

    private String normalizeManagementScopeId(ManagementScopeType managementScopeType, String managementScopeId) {
        if (managementScopeType == ManagementScopeType.PLATFORM) {
            return null;
        }
        return Preconditions.requireText(managementScopeId, "managementScopeId");
    }

    private String resolveGrantablePermissionActionCode(String moduleAlias, String actionCode) {
        String validModuleAlias = requireModuleAlias(moduleAlias);
        String requestedActionCode = requireActionCode(actionCode);
        return grantVerifier.resolveGrantablePermissionActionCode(validModuleAlias, requestedActionCode);
    }

    private String permissionActionCode(String actionCode) {
        return PlatformAction.fromCode(requireActionCode(actionCode))
                .map(action -> action.executionPolicy().permissionActionCode())
                .orElse(actionCode);
    }

    private String requireModuleAlias(String moduleAlias) {
        String valid = Preconditions.requireText(moduleAlias, "moduleAlias");
        try {
            PlatformAliasRules.requireModuleAlias(valid);
        } catch (IllegalArgumentException ex) {
            throw new PlatformException("invalid moduleAlias: " + valid);
        }
        return valid;
    }

    private String requireActionCode(String actionCode) {
        return Preconditions.requireText(actionCode, "actionCode");
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeRoleIdCsv(String value) {
        return String.join(",", parseRoleIds(value));
    }

    private Set<String> parseRoleIds(String value) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return ids;
        }
        for (String item : value.split(",")) {
            if (item == null || item.isBlank()) {
                continue;
            }
            ids.add(item.trim());
        }
        return ids;
    }

    private void prepareChildInsert(EntityContract entity) {
        String tenantId = requireActiveTenantMutationContext();
        entity.setTenantId(tenantId);
        EntityLifecycle.prepareInsert(entity, Instant.now());
    }

    private void prepareRoleActionInsert(Role role, EntityContract entity) {
        if (role != null && role.getOwnerScopeType() == RoleOwnerScopeType.PLATFORM) {
            requirePlatformRoleSystemContext();
            entity.setTenantId(null);
            EntityLifecycle.prepareInsert(entity, Instant.now());
            return;
        }
        prepareChildInsert(entity);
    }

    private void prepareChildUpdate(EntityContract entity) {
        EntityLifecycle.prepareUpdate(entity, Instant.now());
    }

    public record ActionGrantCommand(String moduleAlias,
                                     String actionCode,
                                     DataScopePolicy dataScopePolicy,
                                     TenantScopePolicy tenantScopePolicy,
                                     String scopeCondition,
                                     String referenceFieldId,
                                     String referenceActionCode) {
    }

    public record PermissionActionCommand(String moduleAlias,
                                          String actionCode,
                                          boolean granted,
                                          DataScopePolicy dataScopePolicy,
                                          TenantScopePolicy tenantScopePolicy,
                                          String scopeCondition,
                                          String referenceFieldId,
                                          String referenceActionCode) {
    }

    public record ActionRevokeCommand(String moduleAlias, String actionCode) {
    }

    public record DataGrantActionCommand(String actionCode, DataScopePolicy dataScopePolicy, boolean enabled) {
    }

    /**
     * The tenant that owns the candidate-account query and the management scope persisted
     * on the resulting grant. Keeping both values together prevents a caller-supplied
     * scope from drifting away from the role being managed.
     */
    public record AccountRoleBindingScope(String tenantId,
                                          ManagementScopeType managementScopeType,
                                          String managementScopeId) {
    }

    public record RoleGrantMutationResult(String grantId, boolean changed) {
    }
}
