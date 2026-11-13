package net.ximatai.muyun.spring.iam.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared, transactional shadow lockout for absent, disabled and passwordless accounts. */
@Service
public class UnavailablePasswordLoginService {
    private static final String LOCK_SCOPE = "iam.unavailable-password-login";
    private static final Duration RETENTION = Duration.ofDays(1);
    private final UnavailablePasswordLoginDao dao;
    private final PasswordSecurityPolicyResolver policies;

    public UnavailablePasswordLoginService(UnavailablePasswordLoginDao dao, PasswordSecurityPolicyResolver policies) {
        this.dao = Objects.requireNonNull(dao);
        this.policies = Objects.requireNonNull(policies);
    }

    /** A subsequently provisioned account cannot bypass an already active lock for its login name. */
    public LoginFailureFeedback currentLock(String tenantId, String username, Instant at) {
        var state = dao.findById(key(requireTargetTenant(tenantId), username));
        return state != null && state.getLockedUntil() != null && at.isBefore(state.getLockedUntil())
                ? new LoginFailureFeedback(at, state.getLockedUntil(), false, null) : null;
    }

    /** Commits failure state independently of the authentication rejection that follows. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoginFailureFeedback recordFailure(String tenantId, String username, Instant at) {
        String tenant = requireTargetTenant(tenantId);
        String id = key(tenant, username);
        PlatformAbilityRuntime.lockMutationPartition(LOCK_SCOPE, id);
        var state = dao.findById(id);
        if (state != null && state.getLockedUntil() != null && at.isBefore(state.getLockedUntil())) {
            return new LoginFailureFeedback(at, state.getLockedUntil(), false, null);
        }
        var policy = policies.resolve(tenant).lockoutPolicy();
        if (!policy.enabled()) return null;
        boolean insert = state == null;
        if (insert) {
            state = new UnavailablePasswordLogin();
            state.setId(id);
            state.setTenantId(tenant);
            state.setFailedAttempts(0);
        } else if (!at.isBefore(state.getRetainUntil())) {
            state.setFailedAttempts(0);
        }
        int failures = state.getFailedAttempts() + 1;
        boolean triggered = failures >= policy.maxFailedAttempts();
        state.setFailedAttempts(triggered ? 0 : failures);
        state.setLockedUntil(triggered ? at.plus(policy.lockDuration()) : null);
        state.setRetainUntil((triggered ? state.getLockedUntil() : at).plus(RETENTION));
        if (insert) {
            EntityLifecycle.prepareInsert(state, at);
            dao.insert(state);
        } else {
            Integer version = state.getVersion();
            EntityLifecycle.prepareUpdate(state, at);
            if (dao.updateByIdAndVersion(state, version) != 1) throw new IllegalStateException("login failure state changed");
        }
        if (triggered) return new LoginFailureFeedback(at, state.getLockedUntil(), true, null);
        int remaining = policy.maxFailedAttempts() - failures;
        return remaining <= 2 ? new LoginFailureFeedback(at, null, false, remaining) : null;
    }

    /** Bounded retention using the same partition lock as attempts, so cleanup cannot race a refresh. */
    @Transactional
    public int purgeExpired(Instant at, int limit) {
        if (!TenantContext.isSystem()) {
            throw new PlatformAccessDeniedException("login failure retention requires system scope");
        }
        int deleted = 0;
        for (var candidate : dao.query(Criteria.of().lt("retainUntil", at), new PageRequest(0, limit))) {
            PlatformAbilityRuntime.lockMutationPartition(LOCK_SCOPE, candidate.getId());
            var current = dao.findById(candidate.getId());
            if (current != null && current.getRetainUntil().isBefore(at)) {
                deleted += dao.deleteByIdAndVersion(current.getId(), current.getVersion());
            }
        }
        return deleted;
    }

    private static String requireTargetTenant(String tenantId) {
        String tenant = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
        if (!TenantContext.isSystem()
                && !Objects.equals(tenant, TenantContext.currentTenantId().orElse(null))) {
            throw new PlatformAccessDeniedException("login failure target does not match tenant scope");
        }
        return tenant;
    }

    static String key(String tenant, String username) {
        String name = Objects.requireNonNull(username, "username").trim();
        String framed = (tenant == null ? "-1:" : tenant.length() + ":" + tenant) + name.length() + ":" + name;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(framed.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
