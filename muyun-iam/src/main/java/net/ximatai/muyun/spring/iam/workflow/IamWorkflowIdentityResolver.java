package net.ximatai.muyun.spring.iam.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.employee.*;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.role.*;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowIdentityResolver;
import net.ximatai.muyun.spring.platform.workflow.WorkflowParticipantRule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class IamWorkflowIdentityResolver implements WorkflowIdentityResolver {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final UserAccountService users;
    private final EmployeeService employees;
    private final EmployeeAccountService accounts;
    private final EmployeePositionService positions;
    private final DepartmentService departments;
    private final OrganizationService organizations;
    private final RoleService roles;
    private final AccountRoleGrantDao accountGrants;
    private final EmploymentRoleGrantDao employmentGrants;

    public IamWorkflowIdentityResolver(UserAccountService users, EmployeeService employees,
            EmployeeAccountService accounts, EmployeePositionService positions, DepartmentService departments,
            OrganizationService organizations, RoleService roles, AccountRoleGrantDao accountGrants,
            EmploymentRoleGrantDao employmentGrants) {
        this.users = users; this.employees = employees; this.accounts = accounts; this.positions = positions;
        this.departments = departments; this.organizations = organizations; this.roles = roles;
        this.accountGrants = accountGrants; this.employmentGrants = employmentGrants;
    }

    @Override public boolean isEnabledUser(String id) {
        String tenant = TenantContext.currentTenantId().orElse(null);
        try (var lookup = TenantContext.bypassTenantFilter("workflow participant account eligibility")) {
            var account = users.select(id);
            return account != null && Boolean.TRUE.equals(account.getEnabled())
                    && (account.getTenantId() == null || java.util.Objects.equals(account.getTenantId(), tenant));
        }
    }

    @Override public List<String> resolve(WorkflowParticipantRule rule, String originUserId, String organizationId) {
        return switch (rule.type()) {
            case "ROLE" -> roleUsers(rule.ids(), organizationId);
            case "DEPT" -> scopeUsers(rule.ids(), true, Boolean.TRUE.equals(rule.headOnly()));
            case "ORG" -> scopeUsers(rule.ids(), false, Boolean.TRUE.equals(rule.headOnly()));
            case "RELATIVE" -> relativeUsers(rule, originUserId, organizationId);
            default -> throw new PlatformException("unsupported IAM workflow participant: " + rule.type());
        };
    }

    private List<String> roleUsers(List<String> roleIds, String organizationId) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> expanded = new LinkedHashSet<>();
        for (String id : roleIds) expandRole(id, expanded);
        for (String roleId : expanded) {
            var direct = accountGrants.query(tenantCriteria().eq("roleId", roleId).eq("enabled", true), ALL);
            direct.stream().filter(grant -> matchesManagementScope(grant, organizationId))
                    .forEach(grant -> ids.add(grant.getUserId()));
            for (var grant : employmentGrants.query(tenantCriteria().eq("roleId", roleId).eq("enabled", true), ALL)) {
                var position = tenantRecord(positions.select(grant.getEmployeePositionId()));
                if (position != null && Boolean.TRUE.equals(position.getEnabled())
                        && (organizationId == null || organizationId.equals(position.getOrganizationId())))
                    ids.addAll(userIds(List.of(position.getEmployeeId())));
            }
        }
        return List.copyOf(ids);
    }

    private boolean matchesManagementScope(AccountRoleGrant grant, String organizationId) {
        var type = grant.getManagementScopeType() == null ? ManagementScopeType.TENANT : grant.getManagementScopeType();
        return switch (type) {
            case PLATFORM -> grant.getManagementScopeId() == null;
            case TENANT -> grant.getManagementScopeId() != null
                    && grant.getManagementScopeId().equals(TenantContext.currentTenantId().orElse(null));
            case ORGANIZATION -> {
                if (organizationId == null || grant.getManagementScopeId() == null) yield false;
                var organization = tenantRecord(organizations.select(organizationId));
                yield organization != null && Boolean.TRUE.equals(organization.getEnabled())
                        && organizations.selfAndDescendantIds(tenantCriteria().eq("enabled", true),
                        grant.getManagementScopeId()).contains(organizationId);
            }
        };
    }

    private void expandRole(String id, Set<String> expanded) {
        if (!expanded.add(id)) return;
        var role = tenantRecord(roles.select(id));
        if (role == null || !Boolean.TRUE.equals(role.getEnabled())) { expanded.remove(id); return; }
        if (role.getMemberRoleIds() != null && !role.getMemberRoleIds().isBlank()) {
            String text = role.getMemberRoleIds().replace("[", "").replace("]", "").replace("\"", "");
            for (String member : text.split("[,;]")) if (!member.isBlank()) expandRole(member.trim(), expanded);
        }
    }

    private List<String> scopeUsers(List<String> scopeIds, boolean department, boolean headOnly) {
        if (scopeIds.isEmpty()) throw new PlatformException("参与人组织范围不能为空");
        List<String> employeeIds = new ArrayList<>();
        for (String id : scopeIds) {
            String manager;
            if (department) {
                var scope = tenantRecord(departments.select(id));
                if (scope == null || !Boolean.TRUE.equals(scope.getEnabled())) continue;
                manager = scope.getManagerEmployeeId();
            } else {
                var scope = tenantRecord(organizations.select(id));
                if (scope == null || !Boolean.TRUE.equals(scope.getEnabled())) continue;
                manager = scope.getManagerEmployeeId();
            }
            if (headOnly) {
                if (manager != null) employeeIds.add(manager);
            } else {
                positions.list(tenantCriteria().eq(department ? "departmentId" : "organizationId", id).eq("enabled", true), ALL)
                        .forEach(position -> employeeIds.add(position.getEmployeeId()));
                employees.list(tenantCriteria().eq(department ? "departmentId" : "organizationId", id).eq("enabled", true), ALL)
                        .forEach(employee -> employeeIds.add(employee.getId()));
            }
        }
        return userIds(employeeIds);
    }

    private List<String> relativeUsers(WorkflowParticipantRule rule, String originUserId, String organizationId) {
        String relation = rule.relation() == null ? "SUPERVISOR" : rule.relation().toUpperCase(java.util.Locale.ROOT);
        if ("SELF".equals(relation)) return originUserId == null ? List.of() : List.of(originUserId);
        if (originUserId == null || originUserId.isBlank()) return List.of();
        var binding = accounts.list(tenantCriteria().eq("userId", originUserId), PageRequest.of(1, 1)).stream().findFirst();
        if (binding.isEmpty()) return List.of();
        var employee = tenantRecord(employees.select(binding.get().getEmployeeId()));
        if (employee == null || !Boolean.TRUE.equals(employee.getEnabled())) return List.of();
        int depth = rule.depth();
        if (depth < 0 || depth > 32) throw new PlatformException("参与人关系层级必须在 0 到 32 之间");
        Set<String> visited = new HashSet<>();
        if ("SUPERVISOR".equals(relation)) {
            visited.add(employee.getId());
            for (int index = 0; index < Math.max(1, depth); index++) {
                if (employee.getSupervisorEmployeeId() == null) return List.of();
                employee = tenantRecord(employees.select(employee.getSupervisorEmployeeId()));
                if (employee == null || !Boolean.TRUE.equals(employee.getEnabled())) return List.of();
                if (!visited.add(employee.getId())) throw new PlatformException("员工汇报关系存在循环");
            }
            return userIds(List.of(employee.getId()));
        }
        if ("DEPARTMENT".equals(relation) || "DEPARTMENT_MANAGER".equals(relation)) {
            var department = tenantRecord(departments.select(employee.getDepartmentId()));
            if (department != null) visited.add(department.getId());
            for (int index = 0; index < depth && department != null; index++) {
                if (!Boolean.TRUE.equals(department.getEnabled())) return List.of();
                department = department.getParentId() == null ? null : tenantRecord(departments.select(department.getParentId()));
                if (department != null && !visited.add(department.getId())) throw new PlatformException("部门层级存在循环");
            }
            return department == null ? List.of() : scopeUsers(List.of(department.getId()), true,
                    relation.endsWith("MANAGER") || Boolean.TRUE.equals(rule.headOnly()));
        }
        if ("ORGANIZATION".equals(relation) || "ORGANIZATION_MANAGER".equals(relation)) {
            var organization = tenantRecord(organizations.select(organizationId == null ? employee.getOrganizationId() : organizationId));
            if (organization != null) visited.add(organization.getId());
            for (int index = 0; index < depth && organization != null; index++) {
                if (!Boolean.TRUE.equals(organization.getEnabled())) return List.of();
                organization = organization.getParentId() == null ? null : tenantRecord(organizations.select(organization.getParentId()));
                if (organization != null && !visited.add(organization.getId())) throw new PlatformException("机构层级存在循环");
            }
            return organization == null ? List.of() : scopeUsers(List.of(organization.getId()), false,
                    relation.endsWith("MANAGER") || Boolean.TRUE.equals(rule.headOnly()));
        }
        throw new PlatformException("不支持的参与人关系: " + relation);
    }

    private List<String> userIds(List<String> employeeIds) {
        Set<String> result = new LinkedHashSet<>();
        for (String employeeId : employeeIds) {
            var employee = tenantRecord(employees.select(employeeId));
            if (employee == null || !Boolean.TRUE.equals(employee.getEnabled())) continue;
            accounts.list(tenantCriteria().eq("employeeId", employeeId), ALL).forEach(account -> result.add(account.getUserId()));
        }
        return List.copyOf(result);
    }

    private Criteria tenantCriteria() {
        var criteria = Criteria.of();
        criteria.eqNullable("tenantId", TenantContext.currentTenantId().orElse(null));
        return criteria;
    }

    private <T extends net.ximatai.muyun.spring.common.model.contract.EntityContract> T tenantRecord(T record) {
        return record != null && java.util.Objects.equals(record.getTenantId(), TenantContext.currentTenantId().orElse(null)) ? record : null;
    }
}
