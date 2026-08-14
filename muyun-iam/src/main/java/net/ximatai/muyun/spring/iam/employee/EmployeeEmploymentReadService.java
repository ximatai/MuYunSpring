package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EmployeeEmploymentReadService {
    private final EmployeePositionService employeePositionService;
    private final EmployeeService employeeService;
    private final EmployeeAccountService employeeAccountService;
    private final UserAccountService userAccountService;

    public EmployeeEmploymentReadService(EmployeePositionService employeePositionService, EmployeeService employeeService,
                                         EmployeeAccountService employeeAccountService,
                                         UserAccountService userAccountService) {
        this.employeePositionService = employeePositionService; this.employeeService = employeeService;
        this.employeeAccountService = employeeAccountService;
        this.userAccountService = userAccountService;
    }

    public PageResult<EmployeeEmploymentView> page(Query query) {
        return page(query, Map.of());
    }

    /** Reads employment rows while retaining the already-authorized employee root projection. */
    public PageResult<EmployeeEmploymentView> pageForEmployee(Employee employee, Query query) {
        if (employee == null || employee.getId() == null || employee.getId().isBlank()) {
            throw new IllegalArgumentException("employee must not be null and must have an id");
        }
        Query normalized = query == null ? Query.defaults() : query;
        if (normalized.employeeId() != null && !normalized.employeeId().isBlank()
                && !employee.getId().equals(normalized.employeeId().trim())) {
            throw new IllegalArgumentException("employee query does not match the retained root");
        }
        return page(new Query(employee.getId(), normalized.organizationId(), normalized.departmentId(),
                normalized.enabledOnly(), normalized.pageRequest()), Map.of(employee.getId(), employee));
    }

    private PageResult<EmployeeEmploymentView> page(Query query, Map<String, Employee> employeeOverrides) {
        Query normalized = query == null ? Query.defaults() : query;
        Criteria criteria = Criteria.of();
        if (!Boolean.FALSE.equals(normalized.enabledOnly())) criteria.eq("enabled", Boolean.TRUE);
        if (normalized.employeeId() != null && !normalized.employeeId().isBlank()) criteria.eq("employeeId", normalized.employeeId().trim());
        if (normalized.organizationId() != null && !normalized.organizationId().isBlank()) criteria.eq("organizationId", normalized.organizationId().trim());
        if (normalized.departmentId() != null && !normalized.departmentId().isBlank()) criteria.eq("departmentId", normalized.departmentId().trim());
        PageResult<EmployeePosition> page = employeePositionService.pageQuery(criteria, normalized.pageRequest(), Sort.asc("employeeId"));
        return PageResult.of(views(page.getRecords(), employeeOverrides), page.getTotal(), normalized.pageRequest());
    }

    private List<EmployeeEmploymentView> views(List<EmployeePosition> relations,
                                               Map<String, Employee> employeeOverrides) {
        if (relations.isEmpty()) return List.of();
        List<String> employeeIds = distinctIds(relations, EmployeePosition::getEmployeeId);
        Map<String, Employee> employees = new java.util.LinkedHashMap<>(
                byId(employeeService.list(Criteria.of().in("id", employeeIds), pageOf(employeeIds))));
        employees.putAll(employeeOverrides);
        Map<String, net.ximatai.muyun.spring.iam.employee.EmployeeAccount> accountsByEmployee = employeeAccountService.list(
                Criteria.of().in("employeeId", employeeIds), pageOf(employeeIds)).stream()
                .collect(Collectors.toMap(net.ximatai.muyun.spring.iam.employee.EmployeeAccount::getEmployeeId,
                        Function.identity(), (left, right) -> left));
        List<String> userIds = distinctIds(accountsByEmployee.values(), net.ximatai.muyun.spring.iam.employee.EmployeeAccount::getUserId);
        Map<String, UserAccount> users = byId(userAccountService.list(Criteria.of().in("id", userIds), pageOf(userIds)));
        return relations.stream().map(relation -> view(relation, employees, accountsByEmployee, users)).toList();
    }

    private EmployeeEmploymentView view(EmployeePosition relation, Map<String, Employee> employees,
                                        Map<String, net.ximatai.muyun.spring.iam.employee.EmployeeAccount> accountsByEmployee,
                                        Map<String, UserAccount> users) {
        Employee employee = employees.get(relation.getEmployeeId());
        var account = accountsByEmployee.get(relation.getEmployeeId());
        UserAccount user = account == null ? null : users.get(account.getUserId());
        return new EmployeeEmploymentView(relation.getId(), relation.getVersion(), relation.getEmployeeId(),
                employee == null ? null : employee.getEmployeeNo(), employee == null ? null : employee.getTitle(),
                relation.getOrganizationId(), relation.getOrganizationTitle(),
                relation.getDepartmentId(), relation.getDepartmentTitle(),
                relation.getPositionId(), relation.getPositionTitle(), relation.getPrimaryPosition(),
                relation.getEnabled(), user == null ? null : user.getUsername());
    }

    private static PageRequest pageOf(Collection<String> ids) {
        return new PageRequest(0, Math.max(1, ids.size()));
    }

    private static <T extends EntityContract> Map<String, T> byId(List<T> records) {
        return records.stream().collect(Collectors.toMap(EntityContract::getId, Function.identity(), (left, right) -> left));
    }

    private static <T> List<String> distinctIds(Collection<T> values, Function<T, String> idOf) {
        return values.stream().map(idOf).filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
    }

    public record Query(String employeeId, String organizationId, String departmentId, Boolean enabledOnly,
                        PageRequest pageRequest) {
        static Query defaults() { return new Query(null, null, null, Boolean.TRUE, new PageRequest(0, 50)); }
        public PageRequest pageRequest() { return pageRequest == null ? new PageRequest(0, 50) : pageRequest; }
    }
    public record EmployeeEmploymentView(String id, Integer version, String employeeId, String employeeNo,
                                         String employeeTitle, String organizationId, String organizationTitle,
                                         String departmentId, String departmentTitle, String positionId,
                                         String positionTitle, Boolean primaryPosition, Boolean enabled,
                                         String username) { }
}
