package net.ximatai.muyun.spring.iam.user;

public record UserAccountView(
        String id,
        String tenantId,
        String username,
        String title,
        Boolean enabled
) {
    public static UserAccountView of(UserAccount user) {
        if (user == null) {
            return null;
        }
        return new UserAccountView(
                user.getId(),
                user.getTenantId(),
                user.getUsername(),
                user.getTitle(),
                user.getEnabled()
        );
    }
}
