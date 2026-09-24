package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits one recovery node together with its success journal, independently of other nodes. */
@Service
public class DeletionRecoveryExecutor {
    private final DeletionLogService log;

    public DeletionRecoveryExecutor(DeletionLogService log) {
        this.log = log;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int restore(SoftDeleteAbility<?> ability, String recordId, String entryId) {
        int restored = ability.restore(recordId);
        if (restored > 0) log.completeEntry(entryId, DeletionEntryStatus.SUCCEEDED, null);
        return restored;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purge(RecycleBinAbility<?> ability, String recordId, String entryId) {
        int purged = ability.purge(recordId);
        if (purged > 0) log.completeEntry(entryId, DeletionEntryStatus.SUCCEEDED, null);
        return purged;
    }
}
