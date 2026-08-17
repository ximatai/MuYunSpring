package net.ximatai.muyun.spring.platform.notification;

import java.util.List;

/** Recipient dimensions are combined as a union. */
public record BusinessNotificationRecipients(
        boolean systemWide,
        List<String> tenantIds,
        List<String> organizationIds,
        List<String> departmentIds,
        List<String> positionIds,
        List<String> userIds
) {
    public BusinessNotificationRecipients {
        tenantIds = normalized(tenantIds);
        organizationIds = normalized(organizationIds);
        departmentIds = normalized(departmentIds);
        positionIds = normalized(positionIds);
        userIds = normalized(userIds);
    }

    public static BusinessNotificationRecipients none() {
        return new BusinessNotificationRecipients(false, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> normalized(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }
}
