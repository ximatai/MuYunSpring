package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Direct reference dependencies owned by concrete cache entries, including list snapshots. */
public final class ReferenceDependencyRegistry {
    private static final Map<TargetKey, Set<Registration>> REFERRERS = new HashMap<>();
    private static final Set<Registration> ENTRIES = new HashSet<>();

    private ReferenceDependencyRegistry() {}

    /** Collects persisted reference facts without running business read hooks or aggregate loading. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends EntityContract> Map<ReferenceTarget, Map<String, Set<String>>> collect(
            CacheAbility<T> ability, List<T> records) {
        Map<ReferenceTarget, Map<String, Set<String>>> result = new LinkedHashMap<>();
        for (T stored : records) {
            T record = ability.copyForCache(stored);
            if (ability instanceof FieldProtectionAbility protection) {
                protection.restoreProtectedFieldsFromStorage(record);
            }
            Map<ReferenceTarget, Set<String>> references = ability instanceof ReferencerAbility referencer
                    ? referencer.collectReferenceIdsByTarget(record)
                    : StaticReferenceResolver.collect(ability.modelClass() == null ? record.getClass() : ability.modelClass(), record);
            references.forEach((target, ids) -> ids.forEach(id -> result
                    .computeIfAbsent(target, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(record.getId())));
        }
        return result;
    }

    /** CacheRegistry installs and removes each registration together with its exact cache entry. */
    public static synchronized Registration register(String namespace,
            Map<ReferenceTarget, Map<String, Set<String>>> references, Runnable invalidate) {
        Map<TargetKey, Set<String>> targets = new HashMap<>();
        references.forEach((target, ids) -> ids.forEach((id, referrers) ->
                targets.put(new TargetKey(target, id), Set.copyOf(referrers))));
        Registration entry = new Registration(namespace, Map.copyOf(targets), invalidate);
        if (!targets.isEmpty()) {
            ENTRIES.add(entry);
            targets.keySet().forEach(target -> REFERRERS.computeIfAbsent(target, ignored -> new HashSet<>()).add(entry));
        }
        return entry;
    }

    static void clearReferrers(ReferenceTarget target, String id) {
        if (target == null || id == null || id.isBlank()) return;
        TransactionScopeSupport.afterCommitOrNow(() -> {
            net.ximatai.muyun.spring.ability.CacheRegistry.invalidatePendingLoads();
            List<Registration> entries;
            synchronized (ReferenceDependencyRegistry.class) {
                entries = List.copyOf(REFERRERS.getOrDefault(new TargetKey(target, id), Set.of()));
            }
            // Never call the cache while holding the index lock: cache removal releases registrations.
            entries.forEach(entry -> entry.invalidate.run());
        });
    }

    public static synchronized void clearAll() {
        REFERRERS.clear();
        ENTRIES.clear();
    }

    public static synchronized void clearNamespacePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return;
        ENTRIES.stream().filter(entry -> entry.namespace.equals(prefix) || entry.namespace.startsWith(prefix + "::"))
                .toList().forEach(Registration::close);
    }

    static synchronized Set<String> referrerIds(ReferenceTarget target, String id) {
        TargetKey key = new TargetKey(target, id);
        Set<String> ids = new HashSet<>();
        REFERRERS.getOrDefault(key, Set.of()).forEach(entry -> ids.addAll(entry.targets.get(key)));
        return Set.copyOf(ids);
    }

    public static final class Registration implements AutoCloseable {
        private final String namespace;
        private final Map<TargetKey, Set<String>> targets;
        private final Runnable invalidate;

        private Registration(String namespace, Map<TargetKey, Set<String>> targets, Runnable invalidate) {
            this.namespace = namespace;
            this.targets = targets;
            this.invalidate = invalidate;
        }

        @Override public void close() {
            synchronized (ReferenceDependencyRegistry.class) {
                if (!ENTRIES.remove(this)) return;
                for (TargetKey target : targets.keySet()) {
                    Set<Registration> entries = REFERRERS.get(target);
                    entries.remove(this);
                    if (entries.isEmpty()) REFERRERS.remove(target);
                }
            }
        }
    }

    private record TargetKey(ReferenceTarget target, String id) {}
}
