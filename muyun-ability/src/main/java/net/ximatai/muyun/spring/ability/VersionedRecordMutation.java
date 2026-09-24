package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Conditional storage writes for internal state, such as authentication counters.
 * The caller owns authorization and must supply a fresh, scoped RAW read on every attempt.
 * This does not execute business validation, field protection or Service lifecycle hooks;
 * ordinary business commands must use the standard Service mutation entry points instead.
 */
public final class VersionedRecordMutation {
    private static final int MAX_ATTEMPTS = 64;

    private VersionedRecordMutation() {}

    /** The mutation may be retried and must only change the supplied record, without side effects. */
    public static <T extends EntityContract> T update(BaseDao<T, String> dao, Supplier<T> read,
                                                     Consumer<T> mutation) {
        Objects.requireNonNull(dao, "dao");
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(mutation, "mutation");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            T stored = read.get();
            if (stored == null) return null;
            Integer version = Objects.requireNonNull(stored.getVersion(), "persisted record version is required");
            T draft = EntityRecordCopies.forFieldMutation(stored);
            mutation.accept(draft);
            if (!Objects.equals(stored.getId(), draft.getId())
                    || !Objects.equals(stored.getTenantId(), draft.getTenantId())) {
                throw new IllegalArgumentException("state mutation cannot change record identity or tenant");
            }
            EntityLifecycle.prepareUpdate(draft, Instant.now(), EntityLifecycle.nextVersion(version));
            if (dao.updateByIdAndVersion(draft, version) == 1) return draft;
        }
        throw new OptimisticLockException("记录状态发生并发变化，请重试");
    }
}
