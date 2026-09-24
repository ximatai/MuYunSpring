package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

final class CacheInvalidationSupport {
    private CacheInvalidationSupport() {
    }

    static void clearAfterChanged(Object ability, EntityContract entity) {
        if (ability instanceof CacheAbility<?> cacheAbility) {
            if (entity == null || entity.getId() == null) {
                TransactionScopeSupport.afterCommitOrNow(cacheAbility::clearCache);
                return;
            }
            String id = entity.getId();
            TransactionScopeSupport.afterCommitOrNow(() -> cacheAbility.clearItemCache(id));
        }
        // Reference invalidation owns its after-commit boundary. Scheduling it from another
        // after-commit callback would register too late for Spring to execute it.
        if (ability instanceof ReferenceAbility<?> referenceAbility && entity != null && entity.getId() != null) {
            referenceAbility.clearReferenceReferrers(entity.getId());
        }
    }
}
