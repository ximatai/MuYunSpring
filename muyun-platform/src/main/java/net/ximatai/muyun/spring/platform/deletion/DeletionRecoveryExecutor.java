package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.RetainedRecordPurgeSupport;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits one recovery node together with its success journal, independently of other nodes. */
@Service
public class DeletionRecoveryExecutor {
    private final DeletionLogService log;
    private final java.util.concurrent.ConcurrentHashMap<String, SourceLease> sourceLeases = new java.util.concurrent.ConcurrentHashMap<>();

    /** Serializes recovery of one deletion tree on this node, without combining its node transactions. */
    public <T> T withSourceOperation(String sourceOperationId, java.util.function.Supplier<T> operation) {
        SourceLease lease = sourceLeases.compute(sourceOperationId, (key, current) -> {
            SourceLease result = current == null ? new SourceLease() : current;
            result.users++;
            return result;
        });
        lease.lock.lock();
        try {
            return operation.get();
        } finally {
            lease.lock.unlock();
            sourceLeases.compute(sourceOperationId, (key, current) -> --current.users == 0 ? null : current);
        }
    }

    private static final class SourceLease {
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock(true);
        private int users;
    }

    public DeletionRecoveryExecutor(DeletionLogService log) {
        this.log = log;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int restore(SoftDeleteAbility<?> ability, DeletionEntry source, String entryId) {
        requireUnchangedSource(ability, source);
        int restored = ability.restore(source.getResourceRecordId(), source.getResourceVersion());
        if (restored > 0) log.completeEntry(entryId, DeletionEntryStatus.SUCCEEDED, null);
        return restored;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean validatePurge(SoftDeleteAbility<?> ability, DeletionEntry source,
                              DeletionEntry parent, DeletionRecoveryResourceResolver resolver) {
        requireUnchangedSource(ability, source);
        if (ability instanceof RecycleBinAbility<?> recycleBin && recycleBin.isRecycleBinPurgeEnabled()) {
            recycleBin.beforeRecycleBinPurge(source.getResourceRecordId());
        } else if (!resolver.canPurgeAggregateChild(source, parent)) {
            if (!(ability instanceof RecycleBinAbility<?>)) return false;
            throw new UnsupportedOperationException("Recycle-bin purge is not enabled for " + ability.getModuleAlias());
        }
        ability.beforeRetainedRecordPurge(source.getResourceRecordId());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purge(SoftDeleteAbility<?> ability, DeletionEntry source, DeletionEntry parent,
                     DeletionRecoveryResourceResolver resolver, String entryId) {
        requireUnchangedSource(ability, source);
        int purged;
        if (ability instanceof RecycleBinAbility<?> recycleBin && recycleBin.isRecycleBinPurgeEnabled()) {
            purged = recycleBin.purge(source.getResourceRecordId(), source.getResourceVersion());
        } else if (resolver.canPurgeAggregateChild(source, parent)) {
            purged = RetainedRecordPurgeSupport.purge(ability, source.getResourceRecordId(), source.getResourceVersion());
        } else {
            throw new UnsupportedOperationException("Recycle-bin purge is not enabled for " + ability.getModuleAlias());
        }
        if (purged > 0) log.completeEntry(entryId, DeletionEntryStatus.SUCCEEDED, null);
        return purged;
    }
    private void requireUnchangedSource(SoftDeleteAbility<?> ability, DeletionEntry source) {
        DeletionLifecycleEntry latest = log.latestTerminalEntry(source.getResourceModuleAlias(),
                source.getResourceEntityAlias(), source.getResourceRecordId());
        var current = ability.selectIgnoreSoftDelete(source.getResourceRecordId());
        if (source.getResourceVersion() == null || latest == null || !source.getId().equals(latest.entry().getId())
                || current == null || !Boolean.TRUE.equals(current.getDeleted())
                || !java.util.Objects.equals(current.getVersion(), source.getResourceVersion())
                || !java.util.Objects.equals(current.getTenantId(), source.getTenantId())) {
            throw new OptimisticLockException("resource lifecycle changed after the source deletion");
        }
    }
}
