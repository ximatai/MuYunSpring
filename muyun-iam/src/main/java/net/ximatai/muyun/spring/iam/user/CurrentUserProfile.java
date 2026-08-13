package net.ximatai.muyun.spring.iam.user;

import java.util.List;

/** Read model for the signed-in account. Personnel data remains owned by {@code iam.employee}. */
public record CurrentUserProfile(
        String username,
        String timeZone,
        EmployeeProfile employee
) {
    public record EmployeeProfile(
            String id,
            String employeeNo,
            String title,
            String avatarAssetId,
            String mobile,
            String email,
            String organizationId,
            String organizationTitle,
            String departmentId,
            String departmentTitle,
            boolean contactEditable,
            List<PositionProfile> positions
    ) {
        public EmployeeProfile {
            positions = positions == null ? List.of() : List.copyOf(positions);
        }
    }

    public record PositionProfile(String id, String title, boolean primary) {
    }
}
