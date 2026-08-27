package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.user.UserAccount;

import java.util.Map;

/** Stable account-selector projection shared by user and role record actions. */
public record UserSelectorItem(
        String id,
        String username,
        String employeeId,
        String employeeNo,
        String employeeTitle,
        String organizationId,
        String organizationTitle,
        String departmentId,
        String departmentTitle
) {
    static UserSelectorItem from(UserAccount user) {
        return new UserSelectorItem(
                user.getId(), user.getUsername(), null, null, null, null, null, null, null);
    }

    static UserSelectorItem from(Map<String, Object> record) {
        return new UserSelectorItem(
                text(record.get("id")), text(record.get("username")), text(record.get("employeeId")),
                text(record.get("employeeNo")), text(record.get("employeeTitle")),
                text(record.get("employeeOrganizationId")), text(record.get("organizationTitle")),
                text(record.get("employeeDepartmentId")), text(record.get("departmentTitle")));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
