package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;

import java.util.Optional;

/** Resolves static and dynamic reference targets through one platform boundary. */
public final class PlatformReferenceTargetResolver implements ReferenceTargetResolver {
    private final StaticAbilityCatalog staticAbilities;
    private final DynamicRecordRuntime dynamicRuntime;
    private final DynamicRecordService dynamicRecords;

    public PlatformReferenceTargetResolver(StaticAbilityCatalog staticAbilities,
                                           DynamicRecordRuntime dynamicRuntime) {
        this(staticAbilities, dynamicRuntime, null);
    }

    public PlatformReferenceTargetResolver(StaticAbilityCatalog staticAbilities,
                                           DynamicRecordRuntime dynamicRuntime,
                                           DynamicRecordService dynamicRecords) {
        this.staticAbilities = staticAbilities;
        this.dynamicRuntime = dynamicRuntime;
        this.dynamicRecords = dynamicRecords;
    }

    @Override
    public Optional<ReferenceAbility<?>> resolve(ReferenceTarget target) {
        if (staticAbilities != null) {
            Optional<ReferenceAbility<?>> resolved = staticAbilities.findReference(target);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        if (dynamicRecords != null) {
            Optional<ReferenceAbility<?>> scoped = dynamicRecords.referenceAbility(target);
            if (scoped.isPresent()) {
                return scoped;
            }
        }
        return dynamicRuntime == null ? Optional.empty() : dynamicRuntime.referenceAbility(target);
    }

    @Override
    public Optional<ReferencePlan> referencePlan(ReferenceTarget sourceTarget, String sourceField) {
        if (sourceTarget == null || sourceField == null || sourceField.isBlank()) {
            return Optional.empty();
        }
        if (staticAbilities != null) {
            Optional<ReferencePlan> staticPlan = staticAbilities.findReference(sourceTarget)
                    .flatMap(ability -> StaticReferenceResolver.plans(ability.modelClass()).stream()
                            .filter(plan -> sourceField.equals(plan.sourceField())).findFirst());
            if (staticPlan.isPresent()) {
                return staticPlan;
            }
        }
        return dynamicRuntime == null ? Optional.empty() : dynamicRuntime.referencePlan(sourceTarget, sourceField);
    }
}
