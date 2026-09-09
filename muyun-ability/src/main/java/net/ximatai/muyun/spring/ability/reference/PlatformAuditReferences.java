package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

/** Read-only references derived from lifecycle identity; never participate in business writes. */
public final class PlatformAuditReferences {
    private PlatformAuditReferences() { }
    public static List<ReferencePlan> plans() {
        return PlatformFieldPolicy.auditFields().stream().filter(field -> field.referenceModuleAlias() != null)
                .map(field -> new ReferencePlan(field.fieldName(), ReferenceTarget.parse(field.referenceModuleAlias()),
                        ReferenceCardinality.ONE, List.of(new ReferenceProjection("title", field.fieldName() + "Title")),
                        ReferenceIntegrityPolicy.DEFAULT, ReferenceTenantScope.GLOBAL)).toList();
    }
    public static List<ReferencePlan> availablePlans() {
        return plans().stream().filter(plan -> net.ximatai.muyun.spring.ability.PlatformAbilityRuntime
                .referenceTargetResolver().resolve(plan.target()).isPresent()).toList();
    }
    public static List<ReferencePlan> withStaticPlans(Class<?> type) {
        return EntityContract.class.isAssignableFrom(type)
                ? Stream.concat(StaticReferenceResolver.plans(type).stream(), availablePlans().stream()).toList()
                : StaticReferenceResolver.plans(type);
    }
    public static Map<String, Object> values(EntityContract record) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("createdBy", record.getCreatedBy());
        values.put("createdAt", record.getCreatedAt());
        values.put("updatedBy", record.getUpdatedBy());
        values.put("updatedAt", record.getUpdatedAt());
        return values;
    }
}
