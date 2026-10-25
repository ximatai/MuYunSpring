package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.VersionedRecordMutation;
import net.ximatai.muyun.spring.iam.employee.*;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.user.UserSessionRevocationService;

import org.springframework.stereotype.Component;

/** Source retirement never changes local administrative enablement or manual facts. */
@Component
@ConditionalOnExternalIdentityFederation
public class DirectorySourceLifecycle {
    private final EmployeeAccountService accounts;
    private final EmployeePositionService positions;
    private final RoleService roles;
    private final UserSessionRevocationService sessions;

    public DirectorySourceLifecycle(EmployeeAccountService accounts, EmployeePositionService positions,
                                    RoleService roles, UserSessionRevocationService sessions) {
        this.accounts = accounts;
        this.positions = positions;
        this.roles = roles;
        this.sessions = sessions;
    }

    /** Caller owns the source/admission transaction and its tenant context. Safe to repeat. */
    public void invalidate(ExternalIdentity binding, String status, String runId) {
        String after = null;
        do {
            after = retireBatch(binding, status, runId, after);
        } while (after != null);
        revoke(binding, status);
    }

    /** At most 100 positions; job cursors commit this return value with the mutations. */
    String retireBatch(ExternalIdentity binding, String status, String runId, String after) {
        var link = accounts.accountOfUser(binding.getUserId());
        if (link == null) return null;
        positions.lockParentMutation(link.getEmployeeId());
        var query = Criteria.of().eq("employeeId", link.getEmployeeId())
                .eq("source", "directory").eq("sourceProviderId", binding.getProviderId());
        if (after != null) query.gt("id", after);
        var batch = positions.listRaw(query, new PageRequest(0, 100), Sort.asc("id"));
        for (var position : batch) retire(position, status, runId);
        return batch.isEmpty() ? null : batch.getLast().getId();
    }

    void revoke(ExternalIdentity binding, String status) {
        sessions.revokeUserSessions(binding.getUserId(), "external-source-" + status);
    }

    void retire(EmployeePosition position, String status, String runId) {
        roles.retireDirectoryEmploymentRoles(position.getId(), position.getSourceProviderId());
        if (java.util.Objects.equals(position.getSourceStatus(), status)
                && !Boolean.TRUE.equals(position.getPrimaryPosition())
                && (runId == null || java.util.Objects.equals(position.getLastSyncRunId(), runId))) return;
        VersionedRecordMutation.update(positions.getDao(), () -> positions.selectActiveRaw(position.getId()), row -> {
            row.setSourceStatus(status);
            row.setPrimaryPosition(false);
            if (runId != null) row.setLastSyncRunId(runId);
        });
    }
}
