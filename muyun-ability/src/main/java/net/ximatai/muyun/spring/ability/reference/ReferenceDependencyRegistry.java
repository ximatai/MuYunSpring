package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.CacheRegistry;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReferenceDependencyRegistry {
    private static final Map<TargetKey, Set<ReferrerKey>> REFERRERS = new ConcurrentHashMap<>();
    private static final Map<ReferrerKey, Set<TargetKey>> TARGETS_BY_REFERRER = new ConcurrentHashMap<>();

    private ReferenceDependencyRegistry() {
    }

    /** Internal cache integration; transaction reads bypass both cache and dependencies. */
    public static void refresh(CacheAbility<?> cacheAbility, EntityContract entity) {
        if (entity == null
                || entity.getId() == null
                || entity.getId().isBlank()) {
            return;
        }
        ReferrerKey referrer = new ReferrerKey(cacheAbility.cacheNamespace(), entity.getId());
        removeReferrer(referrer);

        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<ReferenceTarget, Set<String>> references = cacheAbility instanceof ReferencerAbility referencerAbility
                ? referencerAbility.collectReferenceIdsByTarget(entity)
                : StaticReferenceResolver.collect(cacheAbility.modelClass() == null
                        ? entity.getClass() : cacheAbility.modelClass(), entity);
        for (Map.Entry<ReferenceTarget, Set<String>> entry : references.entrySet()) {
            for (String targetId : entry.getValue()) {
                TargetKey target = new TargetKey(entry.getKey(), targetId);
                REFERRERS.computeIfAbsent(target, ignored -> ConcurrentHashMap.newKeySet()).add(referrer);
                TARGETS_BY_REFERRER.computeIfAbsent(referrer, ignored -> ConcurrentHashMap.newKeySet()).add(target);
            }
        }
    }

    public static void removeReferrer(String namespace, String id) {
        if (namespace == null || namespace.isBlank() || id == null || id.isBlank()) {
            return;
        }
        removeReferrer(new ReferrerKey(namespace, id));
    }

    static void clearReferrers(ReferenceTarget target, String id) {
        if (target == null || id == null || id.isBlank()) {
            return;
        }
        TransactionScopeSupport.afterCommitOrNow(() -> clearReferrersNow(target, id));
    }

    private static void clearReferrersNow(ReferenceTarget target, String id) {
        Set<ReferrerKey> referrers = REFERRERS.remove(new TargetKey(target, id));
        if (referrers == null || referrers.isEmpty()) {
            return;
        }
        for (ReferrerKey referrer : referrers) {
            CacheRegistry.removeItem(referrer.namespace(), referrer.id());
            CacheRegistry.clearAllCachePrefix(referrer.namespace() + "::" + CacheAbility.ALL_CACHE_KEY);
            removeReferrer(referrer);
        }
    }

    public static void clearAll() {
        REFERRERS.clear();
        TARGETS_BY_REFERRER.clear();
    }

    public static void clearNamespacePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        TARGETS_BY_REFERRER.keySet().stream()
                .filter(referrer -> referrer.namespace().equals(prefix) || referrer.namespace().startsWith(prefix + "::"))
                .toList()
                .forEach(ReferenceDependencyRegistry::removeReferrer);
    }

    static Set<String> referrerIds(ReferenceTarget target, String id) {
        Set<ReferrerKey> referrers = REFERRERS.get(new TargetKey(target, id));
        if (referrers == null) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        referrers.forEach(referrer -> ids.add(referrer.id()));
        return Collections.unmodifiableSet(ids);
    }

    private static void removeReferrer(ReferrerKey referrer) {
        Set<TargetKey> targets = TARGETS_BY_REFERRER.remove(referrer);
        if (targets == null) {
            return;
        }
        for (TargetKey target : targets) {
            Set<ReferrerKey> referrers = REFERRERS.get(target);
            if (referrers != null) {
                referrers.remove(referrer);
                if (referrers.isEmpty()) {
                    REFERRERS.remove(target, referrers);
                }
            }
        }
    }

    private record TargetKey(ReferenceTarget target, String id) {
    }

    private record ReferrerKey(String namespace, String id) {
    }
}
