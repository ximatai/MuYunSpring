package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserOrganizationResolver;
import net.ximatai.muyun.spring.common.identity.CurrentUserTimeZoneResolver;
import java.util.Objects;

/** One live account projection for authenticated sessions and trusted deferred executions. */
final class UserAccountIdentity {
    private final CurrentUserOrganizationResolver organizations;
    private final CurrentUserTimeZoneResolver timeZones;

    UserAccountIdentity(UserSessionCollaborators collaborators) {
        var resolved = collaborators == null ? UserSessionCollaborators.empty() : collaborators;
        organizations = resolved.organizationResolver(); timeZones = resolved.timeZoneResolver();
    }

    CurrentUser currentUserOf(UserAccount user, boolean passwordChangeRequired) {
        Objects.requireNonNull(user, "user");
        CurrentUser current = user.getTenantId() == null || user.getTenantId().isBlank()
                ? CurrentUser.systemUser(user.getId(), user.getUsername(), passwordChangeRequired)
                : CurrentUser.tenantUser(user.getId(), user.getUsername(), user.getTenantId(), null, passwordChangeRequired);
        current = organizations.resolveOrganizationId(current).map(current::withOrganizationId).orElse(current);
        CurrentUser withOrganization = current;
        return timeZones.resolveZoneId(withOrganization).map(zone -> withOrganization.withTimeZone(zone.getId()))
                .orElse(withOrganization);
    }
}
