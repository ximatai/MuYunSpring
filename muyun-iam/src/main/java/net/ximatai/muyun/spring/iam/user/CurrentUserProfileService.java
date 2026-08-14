package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeePosition;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class CurrentUserProfileService {
    private final EmployeeAccountService employeeAccountService;
    private final EmployeeService employeeService;
    private final EmployeePositionService employeePositionService;

    public CurrentUserProfileService(EmployeeAccountService employeeAccountService,
                                     EmployeeService employeeService,
                                     EmployeePositionService employeePositionService) {
        this.employeeAccountService = employeeAccountService;
        this.employeeService = employeeService;
        this.employeePositionService = employeePositionService;
    }

    public CurrentUserProfile currentProfile(CurrentUser currentUser) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        return new CurrentUserProfile(currentUser.username(), currentUser.timeZone(), employeeProfile(currentUser.userId()));
    }

    @Transactional
    public CurrentUserProfile updateCurrentProfile(CurrentUser currentUser, UpdateCurrentUserProfileRequest request) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        if (request == null) {
            throw new IllegalArgumentException("profile request must not be null");
        }
        String employeeId = employeeAccountService.employeeIdOfUser(currentUser.userId());
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalStateException("current user is not bound to an employee");
        }
        employeeService.updateSelfManagedProfile(employeeId, request.mobile(), request.email(), request.avatarAssetId());
        return currentProfile(currentUser);
    }

    private CurrentUserProfile.EmployeeProfile employeeProfile(String userId) {
        String employeeId = employeeAccountService.employeeIdOfUser(userId);
        if (employeeId == null || employeeId.isBlank()) {
            return null;
        }
        Employee employee = employeeService.select(employeeId);
        if (employee == null) {
            return null;
        }
        return new CurrentUserProfile.EmployeeProfile(employee.getId(), employee.getEmployeeNo(), employee.getTitle(),
                employee.getAvatarAssetId(), employee.getMobile(), employee.getEmail(),
                employee.getOrganizationId(), employee.getOrganizationTitle(),
                employee.getDepartmentId(), employee.getDepartmentTitle(),
                Boolean.TRUE.equals(employee.getEnabled()), positions(employee.getId()));
    }

    private List<CurrentUserProfile.PositionProfile> positions(String employeeId) {
        List<EmployeePosition> relations = employeePositionService.list(
                Criteria.of().eq("employeeId", employeeId).eq("enabled", true), new PageRequest(0, 100));
        return relations
                .stream()
                .map(relation -> new CurrentUserProfile.PositionProfile(relation.getPositionId(),
                        relation.getPositionTitle(), Boolean.TRUE.equals(relation.getPrimaryPosition())))
                .toList();
    }

}
