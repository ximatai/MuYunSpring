package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeePosition;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.position.Position;
import net.ximatai.muyun.spring.iam.position.PositionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class CurrentUserProfileService {
    private final EmployeeAccountService employeeAccountService;
    private final EmployeeService employeeService;
    private final OrganizationService organizationService;
    private final DepartmentService departmentService;
    private final EmployeePositionService employeePositionService;
    private final PositionService positionService;

    public CurrentUserProfileService(EmployeeAccountService employeeAccountService,
                                     EmployeeService employeeService,
                                     OrganizationService organizationService,
                                     DepartmentService departmentService,
                                     EmployeePositionService employeePositionService,
                                     PositionService positionService) {
        this.employeeAccountService = employeeAccountService;
        this.employeeService = employeeService;
        this.organizationService = organizationService;
        this.departmentService = departmentService;
        this.employeePositionService = employeePositionService;
        this.positionService = positionService;
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
        Organization organization = employee.getOrganizationId() == null ? null
                : organizationService.select(employee.getOrganizationId());
        Department department = employee.getDepartmentId() == null ? null
                : departmentService.select(employee.getDepartmentId());
        return new CurrentUserProfile.EmployeeProfile(employee.getId(), employee.getEmployeeNo(), employee.getTitle(),
                employee.getAvatarAssetId(), employee.getMobile(), employee.getEmail(),
                employee.getOrganizationId(), organization == null ? null : organization.getTitle(),
                employee.getDepartmentId(), department == null ? null : department.getTitle(),
                Boolean.TRUE.equals(employee.getEnabled()), positions(employee.getId()));
    }

    private List<CurrentUserProfile.PositionProfile> positions(String employeeId) {
        return employeePositionService.list(Criteria.of().eq("employeeId", employeeId).eq("enabled", true),
                        new PageRequest(0, 100))
                .stream()
                .map(relation -> new CurrentUserProfile.PositionProfile(relation.getPositionId(),
                        titleOf(relation), Boolean.TRUE.equals(relation.getPrimaryPosition())))
                .toList();
    }

    private String titleOf(EmployeePosition relation) {
        Position position = positionService.select(relation.getPositionId());
        return position == null ? null : position.getTitle();
    }

}
