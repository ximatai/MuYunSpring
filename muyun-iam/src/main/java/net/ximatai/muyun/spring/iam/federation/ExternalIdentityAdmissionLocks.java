package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Short admission locks shared with ordinary row updates/deletes; never held during provider I/O. */
@Component
@ConditionalOnExternalIdentityFederation
final class ExternalIdentityAdmissionLocks {
    private final IDatabaseOperations<?> database;

    ExternalIdentityAdmissionLocks(IDatabaseOperations<?> database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    void providerMutation(String providerId) {
        requireTransaction();
        database.query("select id from iam_identity_provider where id = ? for update", providerId);
    }

    boolean configuration(String tenantId, String providerId) {
        requireTransaction();
        // Concurrent admissions may share configuration, while edits and disablement must wait.
        if (database.query("select id from iam_identity_provider where id = ? for share", providerId).isEmpty()) return false;
        return !database.query("select id from iam_tenant_identity_policy where tenant_id = ? and provider_id = ? for share",
                tenantId, providerId).isEmpty();
    }

    boolean identity(String tenantId, String namespace, VerifiedExternalIdentity identity) {
        requireTransaction();
        // Exclusive because a successful login also stamps this binding's last authentication.
        return !database.query("select id from iam_external_identity where tenant_id = ? and identity_namespace = ? "
                        + "and external_enterprise_id = ? and external_user_id = ? for update",
                tenantId, namespace, identity.externalEnterpriseId(), identity.externalUserId()).isEmpty();
    }

    boolean employeeAvailable(String tenantId, String userId) {
        requireTransaction();
        var links = database.query("select employee_id from iam_employee_account where tenant_id = ? and user_id = ? for share",
                tenantId, userId);
        if (links.isEmpty()) return true;
        return !database.query("select id from iam_employee where tenant_id = ? and id = ? and enabled = true for share",
                tenantId, links.getFirst().get("employee_id")).isEmpty();
    }

    Initiator initiator(String tenantId, String userId, String sessionId) {
        requireTransaction();
        // Account before session matches disablement, which revokes sessions after updating the account.
        boolean account = !database.query("select id from iam_user where tenant_id = ? and id = ? for update", tenantId, userId).isEmpty();
        boolean session = account && !database.query("select id from iam_user_session where tenant_id = ? and user_id = ? and id = ? for update",
                tenantId, userId, sessionId).isEmpty();
        return new Initiator(account, session);
    }

    record Initiator(boolean account, boolean session) {}

    private static void requireTransaction() {
        if (!TransactionScopeSupport.isTransactionActive()) {
            throw new IllegalStateException("external identity admission locks require an active transaction");
        }
    }
}
