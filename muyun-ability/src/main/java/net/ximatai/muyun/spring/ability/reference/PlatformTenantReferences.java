package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only tenant-scope label projection.
 *
 * <p>{@code tenantId} remains a platform scope field, rather than becoming a business
 * {@code @ReferenceTo}. The target is resolved through the source-neutral reference-target
 * registry, so this standard projection does not depend on an IAM service implementation.</p>
 */
public final class PlatformTenantReferences {
    public static final String TITLE_FIELD = StandardEntitySchema.TENANT_TITLE_FIELD;
    private static final ReferenceTarget TARGET = ReferenceTarget.of("iam", "tenant");

    private PlatformTenantReferences() {
    }

    public static List<ReferencePlan> plans() {
        return List.of(new ReferencePlan(StandardEntitySchema.TENANT_ID_FIELD, TARGET,
                ReferenceCardinality.ONE,
                List.of(new ReferenceProjection("title", TITLE_FIELD)),
                ReferenceIntegrityPolicy.DEFAULT, ReferenceTenantScope.GLOBAL));
    }

    public static List<ReferencePlan> availablePlans() {
        return PlatformAbilityRuntime.referenceTargetResolver().resolve(TARGET).isPresent()
                ? plans() : List.of();
    }

    public static Map<String, Object> values(EntityContract record) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (record != null) {
            values.put(StandardEntitySchema.TENANT_ID_FIELD, record.getTenantId());
        }
        return values;
    }
}
