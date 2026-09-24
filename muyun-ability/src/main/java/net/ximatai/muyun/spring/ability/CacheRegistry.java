package net.ximatai.muyun.spring.ability;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.ximatai.muyun.spring.ability.reference.ReferenceDependencyRegistry;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CacheRegistry {
    private static final CachePolicy DEFAULT_POLICY = new CachePolicy(1024, Duration.ofMinutes(10));
    private static final ConcurrentMap<String, Cache<String, EntityContract>> ITEM_CACHE = new ConcurrentHashMap<>();
    private static volatile Cache<String, List<? extends EntityContract>> allCache = allCache(DEFAULT_POLICY);

    private static volatile CachePolicy policy = DEFAULT_POLICY;

    private CacheRegistry() {
    }

    private static Cache<String, EntityContract> itemCache(String namespace) {
        return ITEM_CACHE.computeIfAbsent(namespace, key -> boundedItemCache());
    }

    static EntityContract item(String namespace, String id) {
        Cache<String, EntityContract> itemCache = ITEM_CACHE.get(namespace);
        return itemCache == null ? null : itemCache.getIfPresent(id);
    }

    static void putItem(String namespace, String id, EntityContract entity) {
        itemCache(namespace).put(id, entity);
    }

    public static void removeItem(String namespace, String id) {
        Cache<String, EntityContract> itemCache = ITEM_CACHE.get(namespace);
        if (itemCache != null) {
            itemCache.invalidate(id);
            itemCache.cleanUp();
            if (itemCache.estimatedSize() == 0) {
                ITEM_CACHE.remove(namespace, itemCache);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends EntityContract> List<T> allCache(String namespace) {
        return (List<T>) allCache.getIfPresent(namespace);
    }

    static void putAllCache(String namespace, List<? extends EntityContract> records) {
        allCache.put(namespace, records);
    }

    public static void clearNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        ITEM_CACHE.remove(namespace);
        allCache.invalidate(namespace);
    }

    static void clearAllCache(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        allCache.invalidate(namespace);
    }

    public static void clearAllCachePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        allCache.asMap().keySet().removeIf(namespace -> matchesPrefix(namespace, prefix));
    }

    public static void clearNamespacePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        ITEM_CACHE.keySet().removeIf(namespace -> matchesPrefix(namespace, prefix));
        allCache.asMap().keySet().removeIf(namespace -> matchesPrefix(namespace, prefix));
        ReferenceDependencyRegistry.clearNamespacePrefix(prefix);
    }

    public static void clearAll() {
        ITEM_CACHE.clear();
        allCache.invalidateAll();
        ReferenceDependencyRegistry.clearAll();
    }

    public static void configure(CachePolicy nextPolicy) {
        policy = Objects.requireNonNull(nextPolicy, "nextPolicy must not be null");
        clearAll();
        allCache = allCache(policy);
    }

    public static void resetPolicy() {
        configure(DEFAULT_POLICY);
    }

    public static int namespaceCount() {
        HashSet<String> namespaces = new HashSet<>(ITEM_CACHE.keySet());
        namespaces.addAll(allCache.asMap().keySet());
        return namespaces.size();
    }

    static Set<String> itemIds(String namespace) {
        Cache<String, EntityContract> itemCache = ITEM_CACHE.get(namespace);
        if (itemCache == null) {
            return Set.of();
        }
        itemCache.cleanUp();
        return Set.copyOf(itemCache.asMap().keySet());
    }

    private static Cache<String, EntityContract> boundedItemCache() {
        return Caffeine.newBuilder()
                .maximumSize(policy.maxItemsPerNamespace())
                .build();
    }

    private static Cache<String, List<? extends EntityContract>> allCache(CachePolicy policy) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (policy.hasAllCacheTtl()) {
            builder.expireAfterWrite(policy.allCacheTtl());
        }
        return builder.build();
    }

    private static boolean matchesPrefix(String namespace, String prefix) {
        return namespace.equals(prefix) || namespace.startsWith(prefix + "::");
    }

    public record CachePolicy(int maxItemsPerNamespace, Duration allCacheTtl) {
        public CachePolicy {
            if (maxItemsPerNamespace < 1) {
                throw new IllegalArgumentException("maxItemsPerNamespace must be positive");
            }
            allCacheTtl = Objects.requireNonNull(allCacheTtl, "allCacheTtl must not be null");
        }

        private boolean hasAllCacheTtl() {
            return !allCacheTtl.isZero() && !allCacheTtl.isNegative();
        }
    }
}
