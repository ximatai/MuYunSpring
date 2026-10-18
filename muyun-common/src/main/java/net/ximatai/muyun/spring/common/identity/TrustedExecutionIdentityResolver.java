package net.ximatai.muyun.spring.common.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-only identity handoff for deferred work. References carry neither a session nor
 * permission snapshots; every execution still needs live module and record authorization.
 */
public interface TrustedExecutionIdentityResolver {
    Optional<Reference> capture(String moduleAlias, String actionCode);
    Optional<Identity> resolve(Reference reference, String moduleAlias, String actionCode);

    record Reference(String userId, String accountTenantId, Instant accountCreatedAt, Delegation delegation) {
        public Reference {
            userId = identity(userId);
            accountTenantId = optionalIdentity(accountTenantId);
            Objects.requireNonNull(accountCreatedAt, "accountCreatedAt");
        }
    }
    record Delegation(String id, Instant createdAt, String principalEmployeeId, String principalPositionId,
                      String delegateEmployeeId, String delegatePositionId,
                      String selectedPrincipalPositionId, String selectedDelegatePositionId) {
        public Delegation {
            id = identity(id); Objects.requireNonNull(createdAt, "createdAt");
            principalEmployeeId = identity(principalEmployeeId); delegateEmployeeId = identity(delegateEmployeeId);
            principalPositionId = optionalIdentity(principalPositionId); delegatePositionId = optionalIdentity(delegatePositionId);
            selectedPrincipalPositionId = optionalIdentity(selectedPrincipalPositionId);
            selectedDelegatePositionId = optionalIdentity(selectedDelegatePositionId);
        }
    }
    record Identity(CurrentUser user, ActingContext actingContext) {
        public Identity {
            Objects.requireNonNull(user, "user");
            if (actingContext != null && !user.equals(actingContext.operator()))
                throw new IllegalArgumentException("Execution identity must retain the real delegation operator");
        }
    }

    private static String identity(String value) {
        if (value == null || value.isBlank() || value.length() > 64)
            throw new IllegalArgumentException("Invalid execution identity reference");
        return value;
    }
    private static String optionalIdentity(String value) { return value == null ? null : identity(value); }
}
