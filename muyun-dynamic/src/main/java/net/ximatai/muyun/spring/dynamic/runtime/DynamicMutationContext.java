package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DynamicMutationContext implements AutoCloseable {
    private static final ThreadLocal<DynamicMutationContext> CURRENT = new ThreadLocal<>();

    private final Map<DynamicRecord, Runnable> valuePreparations = new java.util.IdentityHashMap<>();
    private final DynamicMutationContext previous;
    private final Instant startedAt;
    private final RuntimeMutationSource mutationSource;
    private final String traceId;
    private final int depth;
    private final String parentExecutionId;
    private final boolean cascadeAllowed;
    private final Map<String, Object> metadata;

    private DynamicMutationContext(DynamicMutationContext previous,
                                   Instant startedAt,
                                   RuntimeMutationSource mutationSource,
                                   String traceId,
                                   int depth,
                                   String parentExecutionId,
                                   boolean cascadeAllowed,
                                   Map<String, Object> metadata,
                                   boolean inheritPreviousMetadata) {
        this.previous = previous;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.mutationSource = mutationSource == null ? RuntimeMutationSource.BUSINESS : mutationSource;
        this.traceId = traceId == null || traceId.isBlank() ? null : traceId;
        this.depth = Math.max(0, depth);
        this.parentExecutionId = parentExecutionId == null || parentExecutionId.isBlank() ? null : parentExecutionId;
        this.cascadeAllowed = cascadeAllowed;
        Map<String, Object> effectiveMetadata = inheritPreviousMetadata && previous != null
                ? previous.metadata
                : metadata;
        this.metadata = effectiveMetadata == null || effectiveMetadata.isEmpty()
                ? Map.of()
                : Map.copyOf(effectiveMetadata);
    }

    public static DynamicMutationContext open(Clock clock) {
        return open(clock, RuntimeMutationSource.BUSINESS, null, Map.of());
    }

    public static DynamicMutationContext open(Clock clock, Map<String, Object> metadata) {
        return open(clock, RuntimeMutationSource.BUSINESS, null, metadata);
    }

    public static DynamicMutationContext open(Clock clock,
                                              RuntimeMutationSource mutationSource,
                                              String traceId,
                                              Map<String, Object> metadata) {
        DynamicMutationContext existing = CURRENT.get();
        Instant startedAt;
        boolean inheritPreviousMetadata = metadata == null && existing != null;
        int depth = existing == null ? 0 : existing.depth;
        String parentExecutionId = existing == null ? null : existing.parentExecutionId;
        boolean cascadeAllowed = existing == null || existing.cascadeAllowed;
        Clock effectiveClock = clock == null ? Clock.systemDefaultZone() : clock;
        startedAt = existing == null ? effectiveClock.instant() : existing.startedAt;
        DynamicMutationContext context = new DynamicMutationContext(existing, startedAt, mutationSource, traceId,
                depth, parentExecutionId, cascadeAllowed, metadata, inheritPreviousMetadata);
        CURRENT.set(context);
        return context;
    }

    public static DynamicMutationContext openWriteBack(Clock clock,
                                                       DynamicWriteBackContext writeBackContext,
                                                       Map<String, Object> metadata) {
        DynamicWriteBackContext effective = writeBackContext == null ? DynamicWriteBackContext.root() : writeBackContext;
        DynamicMutationContext existing = CURRENT.get();
        Instant startedAt = existing == null
                ? (clock == null ? Clock.systemDefaultZone() : clock).instant()
                : existing.startedAt;
        DynamicMutationContext context = new DynamicMutationContext(existing, startedAt, RuntimeMutationSource.WRITE_BACK,
                effective.traceId(), effective.depth(), effective.parentExecutionId(), effective.cascadeAllowed(),
                metadata, false);
        CURRENT.set(context);
        return context;
    }

    public static Optional<DynamicMutationContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Defer platform value generation until defaults, lifecycle and formulas have produced final inputs. */
    public void prepareValuesFor(DynamicRecord record, Runnable preparation) {
        valuePreparations.merge(Objects.requireNonNull(record), Objects.requireNonNull(preparation),
                (first, next) -> () -> { first.run(); next.run(); });
    }

    void prepareValues(DynamicRecord record) {
        Runnable preparation = valuePreparations.remove(record);
        if (preparation != null) preparation.run();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public RuntimeMutationSource mutationSource() {
        return mutationSource;
    }

    public String traceId() {
        return traceId;
    }

    public int depth() {
        return depth;
    }

    public String parentExecutionId() {
        return parentExecutionId;
    }

    public boolean cascadeAllowed() {
        return cascadeAllowed;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public Object metadata(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return metadata.get(key);
    }

    @Override
    public void close() {
        valuePreparations.clear();
        if (CURRENT.get() == this) {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
