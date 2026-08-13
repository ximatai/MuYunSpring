package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserOrganizationResolver;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Uses the bound employee's primary organization as the tenant principal's organization. */
@Service
public class EmployeeCurrentUserOrganizationResolver implements CurrentUserOrganizationResolver {
    private final EmployeeAccountService employeeAccountService;
    private final EmployeeService employeeService;

    public EmployeeCurrentUserOrganizationResolver(EmployeeAccountService employeeAccountService,
                                                   EmployeeService employeeService) {
        this.employeeAccountService = employeeAccountService;
        this.employeeService = employeeService;
    }

    @Override
    public Optional<String> resolveOrganizationId(CurrentUser currentUser) {
        if (currentUser == null || currentUser.system() || currentUser.tenantId() == null) {
            return Optional.empty();
        }
        String employeeId = employeeAccountService.employeeIdOfUser(currentUser.userId());
        if (employeeId == null || employeeId.isBlank()) {
            return Optional.empty();
        }
        Employee employee = employeeService.select(employeeId);
        return employee == null || employee.getOrganizationId() == null || employee.getOrganizationId().isBlank()
                ? Optional.empty()
                : Optional.of(employee.getOrganizationId());
    }
}
