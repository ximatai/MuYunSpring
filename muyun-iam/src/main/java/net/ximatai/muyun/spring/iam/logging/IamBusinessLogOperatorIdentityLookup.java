package net.ximatai.muyun.spring.iam.logging;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentity;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityKey;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityLookup;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.department.DepartmentDao;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountDao;
import net.ximatai.muyun.spring.iam.employee.EmployeeDao;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationDao;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves one page of log-operator display data with bounded IAM DAO reads.
 *
 * <p>This is intentionally a trusted read projection rather than a normal IAM management query:
 * event visibility has already been decided by business-log scope, and applying the reader's
 * current employee or organization data scope here could hide or inconsistently enrich an event
 * that was already authorized. The event's organization snapshot remains authoritative.</p>
 */
@Service
public class IamBusinessLogOperatorIdentityLookup implements BusinessLogOperatorIdentityLookup {
    private final UserAccountDao userAccountDao;
    private final EmployeeAccountDao employeeAccountDao;
    private final EmployeeDao employeeDao;
    private final OrganizationDao organizationDao;
    private final DepartmentDao departmentDao;

    public IamBusinessLogOperatorIdentityLookup(UserAccountDao userAccountDao,
                                                EmployeeAccountDao employeeAccountDao,
                                                EmployeeDao employeeDao,
                                                OrganizationDao organizationDao,
                                                DepartmentDao departmentDao) {
        this.userAccountDao = userAccountDao;
        this.employeeAccountDao = employeeAccountDao;
        this.employeeDao = employeeDao;
        this.organizationDao = organizationDao;
        this.departmentDao = departmentDao;
    }

    @Override
    public Map<BusinessLogOperatorIdentityKey, BusinessLogOperatorIdentity> resolve(
            Collection<BusinessLogOperatorIdentityKey> operatorKeys) {
        List<BusinessLogOperatorIdentityKey> keys = distinctKeys(operatorKeys);
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<String> tenantIds = distinct(keys, BusinessLogOperatorIdentityKey::tenantId);
        List<String> operatorIds = distinct(keys, BusinessLogOperatorIdentityKey::operatorId);
        int pageSize = keys.size();
        Map<Key, UserAccount> users = usersByKey(queryUsers(keys, tenantIds, operatorIds, pageSize));
        if (users.isEmpty()) {
            return Map.of();
        }
        List<String> userIds = users.values().stream().map(UserAccount::getId).distinct().toList();
        Map<Key, EmployeeAccount> bindings = bindingsByUser(queryBindings(tenantIds, userIds, pageSize));
        List<String> employeeIds = bindings.values().stream().map(EmployeeAccount::getEmployeeId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Key, Employee> employees = employeesByKey(queryEmployees(tenantIds, employeeIds, pageSize));
        List<String> organizationIds = distinct(keys, BusinessLogOperatorIdentityKey::operatorOrganizationId);
        Map<Key, Organization> organizations = organizationsByKey(queryOrganizations(tenantIds, organizationIds, pageSize));
        List<String> departmentIds = employees.values().stream().map(Employee::getDepartmentId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Key, Department> departments = departmentsByKey(queryDepartments(tenantIds, departmentIds, pageSize));

        Map<BusinessLogOperatorIdentityKey, BusinessLogOperatorIdentity> result = new LinkedHashMap<>();
        for (BusinessLogOperatorIdentityKey key : keys) {
            Key userKey = new Key(key.tenantId(), key.operatorId());
            UserAccount user = users.get(userKey);
            if (user == null) {
                continue;
            }
            EmployeeAccount binding = bindings.get(userKey);
            Employee employee = binding == null ? null : employees.get(new Key(key.tenantId(), binding.getEmployeeId()));
            Organization organization = organizations.get(new Key(key.tenantId(), key.operatorOrganizationId()));
            Department department = employee == null ? null
                    : departments.get(new Key(key.tenantId(), employee.getDepartmentId()));
            result.put(key, new BusinessLogOperatorIdentity(employeeName(employee), user.getUsername(),
                    key.operatorOrganizationId(),
                    organization == null ? null : organization.getTitle(), department == null ? null : department.getId(),
                    department == null ? null : department.getTitle()));
        }
        return Map.copyOf(result);
    }

    private List<UserAccount> queryUsers(List<BusinessLogOperatorIdentityKey> keys, List<String> tenantIds,
                                         List<String> userIds, int pageSize) {
        List<UserAccount> users = new ArrayList<>();
        if (!tenantIds.isEmpty()) {
            users.addAll(query(userAccountDao, Criteria.of().in("tenantId", tenantIds).in("id", userIds), pageSize));
        }
        List<String> platformUserIds = keys.stream().filter(key -> key.tenantId() == null)
                .map(BusinessLogOperatorIdentityKey::operatorId).distinct().toList();
        if (!platformUserIds.isEmpty()) {
            users.addAll(query(userAccountDao, Criteria.of().isNull("tenantId").in("id", platformUserIds), pageSize));
        }
        return users;
    }

    private List<EmployeeAccount> queryBindings(List<String> tenantIds, List<String> userIds, int pageSize) {
        if (tenantIds.isEmpty() || userIds.isEmpty()) {
            return List.of();
        }
        return query(employeeAccountDao, Criteria.of().in("tenantId", tenantIds).in("userId", userIds), pageSize);
    }

    private List<Employee> queryEmployees(List<String> tenantIds, List<String> employeeIds, int pageSize) {
        if (tenantIds.isEmpty() || employeeIds.isEmpty()) {
            return List.of();
        }
        return query(employeeDao, Criteria.of().in("tenantId", tenantIds).in("id", employeeIds), pageSize);
    }

    private List<Organization> queryOrganizations(List<String> tenantIds, List<String> organizationIds, int pageSize) {
        if (tenantIds.isEmpty() || organizationIds.isEmpty()) {
            return List.of();
        }
        return query(organizationDao, Criteria.of().in("tenantId", tenantIds).in("id", organizationIds), pageSize);
    }

    private List<Department> queryDepartments(List<String> tenantIds, List<String> departmentIds, int pageSize) {
        if (tenantIds.isEmpty() || departmentIds.isEmpty()) {
            return List.of();
        }
        return query(departmentDao, Criteria.of().in("tenantId", tenantIds).in("id", departmentIds), pageSize);
    }

    private static String employeeName(Employee employee) {
        return employee == null ? null : employee.getTitle();
    }

    private static <T extends net.ximatai.muyun.spring.common.model.contract.EntityContract> List<T> query(
            net.ximatai.muyun.spring.ability.BaseDao<T, String> dao, Criteria criteria, int requestedSize) {
        return dao.query(criteria, new PageRequest(0, Math.max(1, requestedSize)));
    }

    private static Map<Key, UserAccount> usersByKey(List<UserAccount> users) {
        return byKey(users, user -> new Key(user.getTenantId(), user.getId()));
    }

    private static Map<Key, EmployeeAccount> bindingsByUser(List<EmployeeAccount> bindings) {
        return byKey(bindings, binding -> new Key(binding.getTenantId(), binding.getUserId()));
    }

    private static Map<Key, Employee> employeesByKey(List<Employee> employees) {
        return byKey(employees, employee -> new Key(employee.getTenantId(), employee.getId()));
    }

    private static Map<Key, Organization> organizationsByKey(List<Organization> organizations) {
        return byKey(organizations, organization -> new Key(organization.getTenantId(), organization.getId()));
    }

    private static Map<Key, Department> departmentsByKey(List<Department> departments) {
        return byKey(departments, department -> new Key(department.getTenantId(), department.getId()));
    }

    private static <T> Map<Key, T> byKey(List<T> records, Function<T, Key> keyOf) {
        return records.stream().collect(Collectors.toMap(keyOf, Function.identity(), (left, right) -> left,
                LinkedHashMap::new));
    }

    private static List<BusinessLogOperatorIdentityKey> distinctKeys(Collection<BusinessLogOperatorIdentityKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
    }

    private static <T> List<String> distinct(Collection<T> values, Function<T, String> valueOf) {
        return values.stream().map(valueOf).filter(Objects::nonNull).filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
    }

    private record Key(String tenantId, String id) { }

}
