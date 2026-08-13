package net.ximatai.muyun.spring.common.identity;

public record CurrentUser(
        String userId,
        String username,
        String tenantId,
        String organizationId,
        boolean system,
        boolean passwordChangeRequired,
        String timeZone
) {
    public CurrentUser {
        userId = requireText(userId, "userId");
        username = normalize(username);
        tenantId = normalize(tenantId);
        organizationId = normalize(organizationId);
        timeZone = normalize(timeZone);
    }

    public static CurrentUser tenantUser(String userId, String username, String tenantId) {
        return tenantUser(userId, username, tenantId, null);
    }

    public static CurrentUser tenantUser(String userId, String username, String tenantId, String organizationId) {
        return tenantUser(userId, username, tenantId, organizationId, false);
    }

    public static CurrentUser tenantUser(String userId,
                                         String username,
                                         String tenantId,
                                         String organizationId,
                                         boolean passwordChangeRequired) {
        return tenantUser(userId, username, tenantId, organizationId, passwordChangeRequired, null);
    }

    public static CurrentUser tenantUser(String userId,
                                         String username,
                                         String tenantId,
                                         String organizationId,
                                         boolean passwordChangeRequired,
                                         String timeZone) {
        return new CurrentUser(userId, username, tenantId, organizationId, false, passwordChangeRequired, timeZone);
    }

    public static CurrentUser systemUser(String userId, String username) {
        return systemUser(userId, username, false);
    }

    public static CurrentUser systemUser(String userId, String username, boolean passwordChangeRequired) {
        return systemUser(userId, username, passwordChangeRequired, null);
    }

    public static CurrentUser systemUser(String userId, String username, boolean passwordChangeRequired,
                                         String timeZone) {
        return new CurrentUser(userId, username, null, null, true, passwordChangeRequired, timeZone);
    }

    public CurrentUser withTimeZone(String timeZone) {
        return new CurrentUser(userId, username, tenantId, organizationId, system, passwordChangeRequired, timeZone);
    }

    public CurrentUser withOrganizationId(String organizationId) {
        return new CurrentUser(userId, username, tenantId, organizationId, system, passwordChangeRequired, timeZone);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
