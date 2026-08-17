package net.ximatai.muyun.spring.iam.notification;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeePosition;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipients;
import net.ximatai.muyun.spring.platform.notification.DefaultBusinessNotificationRecipientResolver;
import org.springframework.stereotype.Service;

/** IAM-aware recipient matching for the currently connected user's employment relations. */
@Service
public class IamBusinessNotificationRecipientResolver extends DefaultBusinessNotificationRecipientResolver {
    private final EmployeeAccountService employeeAccountService;
    private final EmployeePositionService employeePositionService;

    public IamBusinessNotificationRecipientResolver(EmployeeAccountService employeeAccountService,
                                                     EmployeePositionService employeePositionService) {
        this.employeeAccountService = employeeAccountService;
        this.employeePositionService = employeePositionService;
    }

    @Override
    public boolean matches(CurrentUser user, BusinessNotificationRecipients recipients) {
        if (super.matches(user, recipients)) return true;
        if (user == null || recipients == null
                || (recipients.departmentIds().isEmpty() && recipients.positionIds().isEmpty())) return false;
        String employeeId = employeeAccountService.employeeIdOfUser(user.userId());
        if (employeeId == null || employeeId.isBlank()) return false;
        return employeePositionService.positions(employeeId).stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getEnabled()))
                .anyMatch(relation -> recipients.departmentIds().contains(relation.getDepartmentId())
                        || recipients.positionIds().contains(relation.getPositionId()));
    }
}
