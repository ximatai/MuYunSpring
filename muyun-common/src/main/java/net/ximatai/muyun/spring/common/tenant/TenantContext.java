package net.ximatai.muyun.spring.common.tenant;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.Optional;

public final class TenantContext {
    private enum Mode {
        NONE,
        TENANT,
        SYSTEM
    }

    private record State(Mode mode, String tenantId, String systemReason, boolean tenantFilterBypassed) {
        private static State none() {
            return new State(Mode.NONE, null, null, false);
        }
    }

    private static final ThreadLocal<State> CURRENT = ThreadLocal.withInitial(State::none);

    private TenantContext() {
    }

    public static Optional<String> currentTenantId() {
        State state = CURRENT.get();
        return state.mode() == Mode.TENANT ? Optional.of(state.tenantId()) : Optional.empty();
    }

    public static boolean isSystem() {
        return CURRENT.get().mode() == Mode.SYSTEM;
    }

    public static Optional<String> systemReason() {
        State state = CURRENT.get();
        return state.mode() == Mode.SYSTEM ? Optional.ofNullable(state.systemReason()) : Optional.empty();
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
        CURRENT.set(new State(Mode.TENANT, tenantId, null, false));
    }

    public static Scope use(String tenantId) {
        State previous = CURRENT.get();
        setTenantId(tenantId);
        return new Scope(previous);
    }

    public static Scope system(String reason) {
        State previous = CURRENT.get();
        CURRENT.set(new State(Mode.SYSTEM, null, requireReason(reason), false));
        return new Scope(previous);
    }

    public static Scope bypassTenantFilter(String reason) {
        State previous = CURRENT.get();
        requireBypassReason(reason);
        CURRENT.set(new State(previous.mode(), previous.tenantId(), previous.systemReason(), true));
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
