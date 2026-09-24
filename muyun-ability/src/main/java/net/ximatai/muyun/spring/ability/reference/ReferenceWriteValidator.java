package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Shared write integrity for static records, dynamic records and discriminated references. */
public final class ReferenceWriteValidator {
    private ReferenceWriteValidator() {
    }

    public static void validateStatic(Class<?> model, EntityContract existing, EntityContract record) {
        if (record == null || model == null) {
            return;
        }
        var resolver = PlatformAbilityRuntime.referenceTargetResolver();
        // Standalone model-only services have no assembled catalog; platform hosts install it.
        if (resolver == ReferenceTargetResolver.NONE) {
            return;
        }
        for (var rule : StaticReferenceResolver.rules(model)) {
            List<String> ids = StaticReferenceResolver.values(record, rule.plan());
            if (ids.isEmpty()) continue;
            ReferenceAbility<?> target = resolver.resolve(rule.target())
                    .orElseThrow(() -> new PlatformException("reference target is not registered: " + rule.target().qualifiedName()));
            validate(rule.plan(), ids, existing == null ? List.of() : StaticReferenceResolver.values(existing, rule.plan()), record.getTenantId(),
                    field -> StaticReferenceResolver.readLoadedValue(record, field),
                    existing == null ? null : field -> StaticReferenceResolver.readLoadedValue(existing, field), target);
        }
    }

    public static void validate(ReferencePlan plan, Collection<String> ids, Collection<String> persistedIds,
                                String sourceTenant, Function<String, Object> sourceValue,
                                Function<String, Object> persistedSourceValue, ReferenceAbility<?> target) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        plan.integrity().validateTarget(plan.target(), target.supportsEnabledState());
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (plan.tenantScope() == ReferenceTenantScope.SAME_TENANT) fields.add(StandardEntitySchema.TENANT_ID_FIELD);
        if (plan.integrity().requireEnabled()) fields.add(PlatformAbilityFields.ENABLED_FIELD);
        plan.candidateDependencies().forEach(dependency -> fields.add(dependency.targetField()));
        Map<String, Map<String, Object>> facts = target.referenceFacts(ids, fields);
        for (String id : ids) {
            Map<String, Object> fact = facts.get(id);
            if (fact == null) {
                if (!plan.integrity().requireEnabled()
                        && plan.integrity().onTargetUnavailable() == ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY
                        && persistedIds.contains(id)
                        && unchangedDependencies(plan, sourceValue, persistedSourceValue)) {
                    continue;
                }
                throw violation(plan, "TARGET_UNAVAILABLE", "所选关联记录不存在或已删除，请重新选择");
            }
            if (plan.tenantScope() == ReferenceTenantScope.SAME_TENANT
                    && !Objects.equals(sourceTenant, fact.get(StandardEntitySchema.TENANT_ID_FIELD))) {
                throw violation(plan, "TENANT_MISMATCH", "所选关联记录不属于当前记录的租户");
            }
            if (plan.integrity().requireEnabled() && !Boolean.TRUE.equals(fact.get(PlatformAbilityFields.ENABLED_FIELD))) {
                throw violation(plan, "TARGET_DISABLED", "所选关联记录已停用，请重新选择");
            }
            for (var dependency : plan.candidateDependencies()) {
                Object source = sourceValue.apply(dependency.sourceField());
                if (dependency.required() && (source == null || String.valueOf(source).isBlank())) {
                    throw violation(plan, "DEPENDENCY_REQUIRED", "请先填写关联记录所依赖的字段");
                }
                if (source != null && !Objects.equals(String.valueOf(source), String.valueOf(fact.get(dependency.targetField())))) {
                    throw violation(plan, "DEPENDENCY_MISMATCH", "所选关联记录与当前填写的关联条件不一致");
                }
            }
        }
    }

    private static PlatformException violation(ReferencePlan plan, String reason, String message) {
        return new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400, message,
                ErrorScope.empty(), List.of(ErrorTarget.field(plan.sourceField())),
                Map.of("referenceReason", reason, "referenceTarget", plan.target().qualifiedName(),
                        "sourceField", plan.sourceField()));
    }

    private static boolean unchangedDependencies(ReferencePlan plan, Function<String, Object> current,
                                                  Function<String, Object> existing) {
        return plan.candidateDependencies().stream().allMatch(dependency -> existing != null
                && Objects.equals(current.apply(dependency.sourceField()), existing.apply(dependency.sourceField())));
    }
}
