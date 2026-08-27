package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionMode;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;
import net.ximatai.muyun.spring.ability.reference.ReferenceDeletionGuard;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles static inbound references once and applies their target-unavailable policy. */
public final class StaticReferenceDeletionGuard implements ReferenceDeletionGuard {
    private static final int CASCADE_BATCH_SIZE = 200;
    private final Map<ReferenceTarget, List<InboundReference>> inboundReferences;

    public StaticReferenceDeletionGuard(List<CrudAbility<?>> abilities) {
        Map<ReferenceTarget, List<InboundReference>> index = new LinkedHashMap<>();
        for (CrudAbility<?> sourceAbility : abilities == null ? List.<CrudAbility<?>>of() : abilities) {
            Class<?> modelClass = sourceAbility.modelClass();
            if (modelClass == null) {
                continue;
            }
            for (StaticReferenceResolver.ReferenceRule rule : StaticReferenceResolver.rules(modelClass)) {
                index.computeIfAbsent(rule.target(), ignored -> new java.util.ArrayList<>())
                        .add(new InboundReference(sourceAbility, rule));
            }
        }
        this.inboundReferences = index.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }

    @Override
    public void validateTargetUnavailable(CrudAbility<?> targetAbility, EntityContract target) {
        restrict(targetOf(targetAbility), target);
    }

    @Override
    public void cascadeTargetUnavailable(CrudAbility<?> targetAbility,
                                         EntityContract target,
                                         DeletionContext context,
                                         DeletionNode node,
                                         DeletionMode mode) {
        if (target == null || target.getId() == null || target.getId().isBlank()) {
            return;
        }
        ReferenceTarget targetReference = targetOf(targetAbility);
        cascade(targetReference, target.getId(), context, node);
    }

    private void cascade(ReferenceTarget target,
                         String targetId,
                         DeletionContext context,
                         DeletionNode node) {
        for (InboundReference inbound : inboundReferences.getOrDefault(target, List.of())) {
            if (inbound.rule().integrity().onTargetUnavailable() != ReferenceTargetUnavailablePolicy.CASCADE_DELETE) {
                continue;
            }
            java.util.Set<String> attemptedIds = new java.util.LinkedHashSet<>();
            while (true) {
                List<? extends EntityContract> referrers = inbound.source().list(
                        Criteria.of().eq(inbound.rule().plan().sourceField(), targetId),
                        PageRequest.of(1, CASCADE_BATCH_SIZE));
                if (referrers.isEmpty()) {
                    break;
                }
                boolean progressed = false;
                for (EntityContract referrer : referrers) {
                    if (!attemptedIds.add(referrer.getId())) {
                        continue;
                    }
                    int deleted = inbound.source().delete(referrer.getId(), referrer.getVersion(),
                            context.child(node, inbound.source().getModuleAlias(), referrer.getId()));
                    progressed = progressed || deleted > 0;
                }
                if (!progressed) {
                    break;
                }
            }
        }
    }

    private void restrict(ReferenceTarget targetReference, EntityContract target) {
        if (target == null || target.getId() == null || target.getId().isBlank()) {
            return;
        }
        for (InboundReference inbound : inboundReferences.getOrDefault(targetReference, List.of())) {
            if (inbound.rule().integrity().onTargetUnavailable() != ReferenceTargetUnavailablePolicy.RESTRICT) {
                continue;
            }
            long count = inbound.source().count(Criteria.of().eq(inbound.rule().plan().sourceField(), target.getId()));
            if (count > 0) {
                throw new PlatformException("cannot make reference target unavailable " + targetReference.qualifiedName()
                        + ": active records in " + inbound.source().getModuleAlias()
                        + "." + inbound.rule().plan().sourceField() + " still reference it");
            }
        }
    }

    private ReferenceTarget targetOf(CrudAbility<?> ability) {
        return ReferenceTargets.of(ability);
    }

    private record InboundReference(CrudAbility<?> source, StaticReferenceResolver.ReferenceRule rule) {
    }
}
