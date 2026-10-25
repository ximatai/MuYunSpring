package net.ximatai.muyun.spring.common.tenant;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

public final class TenantContext {
    private enum Mode {
        NONE,
        TENANT,
        TENANT_SYSTEM,
        SYSTEM
    }

    private record State(Mode mode, String tenantId, String systemReason, String backgroundJobType, boolean tenantFilterBypassed) {
        private static State none() {
            return new State(Mode.NONE, null, null, null, false);
        }
    }

    private static final ThreadLocal<State> CURRENT = ThreadLocal.withInitial(State::none);

    private TenantContext() {
    }

    public static Optional<String> currentTenantId() {
        State state = CURRENT.get();
        return (state.mode() == Mode.TENANT || state.mode() == Mode.TENANT_SYSTEM)
                ? Optional.of(state.tenantId()) : Optional.empty();
    }

    public static boolean isSystem() {
        return CURRENT.get().mode() == Mode.SYSTEM;
    }

    public static boolean isTenantSystem() {
        return CURRENT.get().mode() == Mode.TENANT_SYSTEM;
    }

    public static Optional<String> systemReason() {
        State state = CURRENT.get();
        return Optional.ofNullable(state.systemReason());
    }

    public static boolean tenantFilterBypassed() {
        return CURRENT.get().tenantFilterBypassed();
    }

    public static boolean hasContext() {
        return CURRENT.get().mode() != Mode.NONE;
    }

    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            CURRENT.set(State.none());
            return;
        }
        CURRENT.set(new State(Mode.TENANT, tenantId, null, null, false));
    }

    public static Scope use(String tenantId) {
        State previous = CURRENT.get();
        setTenantId(tenantId);
        return new Scope(previous);
    }

    public static Scope system(String reason) {
        State previous = CURRENT.get();
        CURRENT.set(new State(Mode.SYSTEM, null, requireReason(reason), null, false));
        return new Scope(previous);
    }

    /** Tenant-bound system work keeps ordinary tenant filtering and does not grant system privileges. */
    public static Scope tenantSystem(String tenantId, String jobType) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant system context requires tenantId");
        }
        String type = requireJobType(jobType);
        State previous = CURRENT.get();
        CURRENT.set(new State(Mode.TENANT_SYSTEM, tenantId, "后台作业 " + type, type, false));
        return new Scope(previous);
    }

    public static Scope backgroundJob(String jobType) {
        String type = requireJobType(jobType);
        State previous = CURRENT.get();
        CURRENT.set(new State(Mode.SYSTEM, null, "后台作业 " + type, type, false));
        return new Scope(previous);
    }

    public static Optional<String> backgroundJobType() {
        return Optional.ofNullable(CURRENT.get().backgroundJobType());
    }

    public static Optional<String> systemOperatorId() {
        return backgroundJobType().map(type -> {
            try {
                return "job:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(type.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        });
    }

    public static Scope bypassTenantFilter(String reason) {
        State previous = CURRENT.get();
        requireBypassReason(reason);
        CURRENT.set(new State(previous.mode(), previous.tenantId(), previous.systemReason(), previous.backgroundJobType(), true));
        return new Scope(previous);
    }

    public static void clear() {
        CURRENT.set(State.none());
    }

    public static void applyToNewEntity(EntityContract entity) {
        if (entity == null) return;
        currentTenantId().ifPresent(tenantId -> {
            String requested = entity.getTenantId();
            if (requested != null && !requested.isBlank() && !tenantId.equals(requested)) {
                if (!tenantFilterBypassed()) {
                    throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException(
                            "new record tenant does not match current tenant context");
                }
                return;
            }
            entity.setTenantId(tenantId);
        });
    }

    public static boolean matchesCurrentTenant(EntityContract entity) {
        if (entity == null) {
            return false;
        }
        if (tenantFilterBypassed()) {
            return true;
        }
        return currentTenantId()
                .map(tenantId -> tenantId.equals(entity.getTenantId()))
                .orElse(true);
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;

        private Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            CURRENT.set(previous);
        }
    }

    private static String requireJobType(String jobType) {
        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("background job context requires jobType");
        }
        return jobType.trim();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("system context requires reason");
        }
        return reason.trim();
    }

    private static String requireBypassReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("tenant filter bypass requires reason");
        }
        return reason.trim();
    }
}
