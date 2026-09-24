package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.util.List;

public interface CacheAbility<T extends EntityContract> extends CrudAbility<T> {
    String ALL_CACHE_KEY = "__all__";

    default String cacheNamespace() {
        return getClass().getName()
                + "::" + getModuleAlias()
                + "::" + System.identityHashCode(getDao());
    }

    default T copyForCache(T entity) {
        return EntityRecordCopies.shallowCopy(entity,
                "cache copy requires a no-arg constructor or custom copyForCache");
    }

    @SuppressWarnings("unchecked")
    default T selectWithCache(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (TransactionScopeSupport.isTransactionActive()) {
            return selectWithoutCache(id);
        }
        T cached = (T) CacheRegistry.item(cacheNamespace(), id);
        if (cached != null) {
            if (!isCacheVisible(cached)) {
                CacheRegistry.removeItem(cacheNamespace(), id);
                return null;
            }
            T copied = copyForCache(cached);
            PlatformAbilityDispatcher.afterSelect(this, copied);
            afterSelect(copied);
            return copied;
        }

        T loaded = getDao().query(activeCriteria(Criteria.of().eq(StandardEntitySchema.ID_FIELD, id)), new PageRequest(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
        if (!isCacheVisible(loaded)) {
            return null;
        }
        CacheRegistry.putItem(cacheNamespace(), id, copyForCache(loaded));
        T copied = copyForCache(loaded);
        PlatformAbilityDispatcher.afterSelect(this, copied);
        afterSelect(copied);
        return copied;
    }

    @SuppressWarnings("unchecked")
    default List<T> selectAllWithCache() {
        if (TransactionScopeSupport.isTransactionActive()) {
            return selectAllWithoutCache();
        }
        List<T> cached = CacheRegistry.allCache(allCacheNamespace());
        if (cached == null) {
            cached = getDao().query(activeCriteria(Criteria.of()), PageRequests.all()).stream()
                    .map(this::copyForCache)
                    .toList();
            CacheRegistry.putAllCache(allCacheNamespace(), cached);
        }
        return cached.stream()
                .map(this::copyForCache)
                .peek(record -> PlatformAbilityDispatcher.afterSelect(this, record))
                .peek(this::afterSelect)
                .toList();
    }

    default void clearItemCache(String id) {
        CacheRegistry.removeItem(cacheNamespace(), id);
        clearAllCache();
    }

    default void clearAllCache() {
        CacheRegistry.clearAllCachePrefix(allCachePrefix());
    }

    default void clearCache() {
        CacheRegistry.clearNamespacePrefix(cacheNamespace());
    }

    private boolean isCacheVisible(T entity) {
        if (entity == null) {
            return false;
        }
        if (this instanceof SoftDeleteAbility<?> && Boolean.TRUE.equals(entity.getDeleted())) {
            return false;
        }
        return TenantContext.matchesCurrentTenant(entity);
    }

    private T selectWithoutCache(String id) {
        T loaded = getDao().query(activeCriteria(Criteria.of().eq(StandardEntitySchema.ID_FIELD, id)), new PageRequest(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
        if (!isCacheVisible(loaded)) {
            return null;
        }
        T copied = copyForCache(loaded);
        PlatformAbilityDispatcher.afterSelect(this, copied);
        afterSelect(copied);
        return copied;
    }

    private List<T> selectAllWithoutCache() {
        return getDao().query(activeCriteria(Criteria.of()), PageRequests.all()).stream()
                .map(this::copyForCache)
                .peek(record -> PlatformAbilityDispatcher.afterSelect(this, record))
                .peek(this::afterSelect)
                .toList();
    }

    private String allCacheNamespace() {
        return allCachePrefix() + "::" + tenantScopeKey();
    }

    private String allCachePrefix() {
        return cacheNamespace() + "::" + ALL_CACHE_KEY;
    }

    private String tenantScopeKey() {
        if (TenantContext.tenantFilterBypassed()) {
            return "all-tenants";
        }
        return TenantContext.currentTenantId()
                .map(tenantId -> "tenant:" + tenantId)
                .orElseGet(() -> TenantContext.isSystem() ? "system" : "none");
    }
}
