package net.ximatai.muyun.spring.ability;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import net.ximatai.muyun.spring.ability.reference.ReferenceDependencyRegistry;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

public final class CacheRegistry {
    private static final java.util.concurrent.atomic.AtomicLong INVALIDATION_GENERATION = new java.util.concurrent.atomic.AtomicLong();
    private static final CachePolicy DEFAULT_POLICY = new CachePolicy(1024, Duration.ofMinutes(10));
    private static final Map<String, Cache<String, Entry<EntityContract>>> ITEM_CACHE = new HashMap<>();
    private static Ticker ticker = Ticker.systemTicker();
    private static Cache<String, Entry<List<? extends EntityContract>>> allCache = allCache(DEFAULT_POLICY);

    private static CachePolicy policy = DEFAULT_POLICY;

    private CacheRegistry() {
    }

    static long generation() {
        return INVALIDATION_GENERATION.get();
    }

    /** Invalidates in-flight fills, including reads whose reference dependencies are not registered yet. */
    public static void invalidatePendingLoads() {
        INVALIDATION_GENERATION.incrementAndGet();
    }

    private static Cache<String, Entry<EntityContract>> itemCache(String namespace) {
        return ITEM_CACHE.computeIfAbsent(namespace, key -> boundedItemCache());
    }

    static synchronized EntityContract item(String namespace, String id) {
        Cache<String, Entry<EntityContract>> itemCache = ITEM_CACHE.get(namespace);
        Entry<EntityContract> entry = itemCache == null ? null : itemCache.getIfPresent(id);
        return entry == null ? null : entry.value;
    }

    static synchronized void putItem(String namespace, String id, EntityContract entity) {
        itemCache(namespace).put(id, new Entry<>(entity));
    }

    static <T extends EntityContract> void putItem(CacheAbility<T> ability, String id, T entity) {
        putItem(ability, id, entity, generation());
    }

    static <T extends EntityContract> void putItem(CacheAbility<T> ability, String id, T entity, long generation) {
        var references = ReferenceDependencyRegistry.collect(ability, List.of(entity));
        synchronized (CacheRegistry.class) {
            if (generation != generation()) return;
            String namespace = ability.cacheNamespace();
            Cache<String, Entry<EntityContract>> cache = itemCache(namespace);
            Entry<EntityContract> entry = new Entry<>(entity);
            entry.dependencies = ReferenceDependencyRegistry.register(namespace, references,
                    () -> removeEntry(cache, id, entry));
            if (generation != generation()) {
                entry.close();
                return;
            }
            cache.put(id, entry);
        }
    }

    private static synchronized <V> void removeEntry(Cache<String, Entry<V>> cache, String key, Entry<V> entry) {
        cache.asMap().remove(key, entry);
    }

    public static synchronized void removeItem(String namespace, String id) {
        invalidatePendingLoads();
        Cache<String, Entry<EntityContract>> itemCache = ITEM_CACHE.get(namespace);
        if (itemCache != null) {
            itemCache.invalidate(id);
            itemCache.cleanUp();
            if (itemCache.estimatedSize() == 0) {
                ITEM_CACHE.remove(namespace, itemCache);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static synchronized <T extends EntityContract> List<T> allCache(String namespace) {
        Entry<List<? extends EntityContract>> entry = allCache.getIfPresent(namespace);
        return entry == null ? null : (List<T>) entry.value;
    }

    static <T extends EntityContract> void putAllCache(CacheAbility<T> ability, String namespace, List<T> records, long generation) {
        var references = ReferenceDependencyRegistry.collect(ability, records);
        synchronized (CacheRegistry.class) {
            Cache<String, Entry<List<? extends EntityContract>>> cache = allCache;
            Entry<List<? extends EntityContract>> entry = new Entry<>(List.copyOf(records));
            entry.dependencies = ReferenceDependencyRegistry.register(ability.cacheNamespace(), references,
                    () -> removeEntry(cache, namespace, entry));
            if (generation != generation()) {
                entry.close();
                return;
            }
            cache.put(namespace, entry);
        }
    }

    public static synchronized void clearNamespace(String namespace) {
        invalidatePendingLoads();
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        Cache<String, Entry<EntityContract>> cache = ITEM_CACHE.remove(namespace);
        if (cache != null) cache.invalidateAll();
        allCache.invalidate(namespace);
    }

    public static synchronized void clearAllCachePrefix(String prefix) {
        invalidatePendingLoads();
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        allCache.asMap().keySet().removeIf(namespace -> matchesPrefix(namespace, prefix));
    }

    public static synchronized void clearNamespacePrefix(String prefix) {
        invalidatePendingLoads();
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        ITEM_CACHE.keySet().stream().filter(namespace -> matchesPrefix(namespace, prefix))
                .toList().forEach(CacheRegistry::clearNamespace);
        allCache.asMap().keySet().removeIf(namespace -> matchesPrefix(namespace, prefix));
        ReferenceDependencyRegistry.clearNamespacePrefix(prefix);
    }

    public static synchronized void clearAll() {
        invalidatePendingLoads();
        ITEM_CACHE.values().forEach(Cache::invalidateAll);
        ITEM_CACHE.clear();
        allCache.invalidateAll();
        ReferenceDependencyRegistry.clearAll();
    }

    public static synchronized void configure(CachePolicy nextPolicy) {
        policy = Objects.requireNonNull(nextPolicy, "nextPolicy must not be null");
        clearAll();
        allCache = allCache(policy);
    }

    public static synchronized void resetPolicy() {
        ticker = Ticker.systemTicker();
        configure(DEFAULT_POLICY);
    }

    public static synchronized int namespaceCount() {
        HashSet<String> namespaces = new HashSet<>(ITEM_CACHE.keySet());
        namespaces.addAll(allCache.asMap().keySet());
        return namespaces.size();
    }

    static synchronized Set<String> itemIds(String namespace) {
        Cache<String, Entry<EntityContract>> itemCache = ITEM_CACHE.get(namespace);
        if (itemCache == null) {
            return Set.of();
        }
        itemCache.cleanUp();
        return Set.copyOf(itemCache.asMap().keySet());
    }

    private static Cache<String, Entry<EntityContract>> boundedItemCache() {
        return Caffeine.newBuilder()
                .maximumSize(policy.maxItemsPerNamespace())
                .executor(Runnable::run)
                .<String, Entry<EntityContract>>removalListener((key, entry, cause) -> { if (entry != null) entry.close(); })
                .build();
    }

    private static Cache<String, Entry<List<? extends EntityContract>>> allCache(CachePolicy policy) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (policy.hasAllCacheTtl()) {
            builder.expireAfterWrite(policy.allCacheTtl());
        }
        return builder.ticker(ticker).executor(Runnable::run)
                .<String, Entry<List<? extends EntityContract>>>removalListener((key, entry, cause) -> {
                    if (entry != null) entry.close();
                }).build();
    }

    static synchronized void cleanUp() {
        ITEM_CACHE.values().forEach(Cache::cleanUp);
        allCache.cleanUp();
    }

    static synchronized void useTicker(Ticker clock) {
        ticker = Objects.requireNonNull(clock);
        configure(policy);
    }

    private static final class Entry<V> {
        private final V value;
        private ReferenceDependencyRegistry.Registration dependencies;

        private Entry(V value) { this.value = value; }
        private void close() { if (dependencies != null) dependencies.close(); }
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
