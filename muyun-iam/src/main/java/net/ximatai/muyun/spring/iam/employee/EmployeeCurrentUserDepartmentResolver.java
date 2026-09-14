package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserDepartmentResolver;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Reads the bound employee only while an event is being captured, then stores its department snapshot. */
@Service
public class EmployeeCurrentUserDepartmentResolver implements CurrentUserDepartmentResolver {
    private final EmployeeAccountService employeeAccountService;
    private final EmployeeService employeeService;

    public EmployeeCurrentUserDepartmentResolver(EmployeeAccountService employeeAccountService,
                                                 EmployeeService employeeService) {
        this.employeeAccountService = employeeAccountService;
        this.employeeService = employeeService;
    }

    @Override
    public Optional<String> resolveDepartmentId(CurrentUser currentUser) {
        if (currentUser == null || currentUser.system() || currentUser.tenantId() == null) return Optional.empty();
        String employeeId = employeeAccountService.employeeIdOfUser(currentUser.userId());
        if (employeeId == null || employeeId.isBlank()) return Optional.empty();
        Employee employee = employeeService.select(employeeId);
        return employee == null || employee.getDepartmentId() == null || employee.getDepartmentId().isBlank()
                ? Optional.empty() : Optional.of(employee.getDepartmentId());
    }
}
