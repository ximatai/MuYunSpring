package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Combines independent standard-field read projections for consumers of the reference pipeline. */
public final class PlatformStandardReferences {
    private PlatformStandardReferences() {
    }

    /** Declared standard projection facts, including targets not yet installed in this runtime. */
    public static List<ReferencePlan> plans() {
        return Stream.concat(PlatformAuditReferences.plans().stream(), PlatformTenantReferences.plans().stream()).toList();
    }

    public static List<ReferencePlan> availablePlans() {
        return Stream.concat(PlatformAuditReferences.availablePlans().stream(),
                PlatformTenantReferences.availablePlans().stream()).toList();
    }

    public static List<ReferencePlan> withStaticPlans(Class<?> type) {
        return EntityContract.class.isAssignableFrom(type)
                ? Stream.concat(StaticReferenceResolver.plans(type).stream(), availablePlans().stream()).toList()
                : StaticReferenceResolver.plans(type);
    }

    public static Map<String, Object> values(EntityContract record) {
        Map<String, Object> values = new LinkedHashMap<>(PlatformAuditReferences.values(record));
        values.putAll(PlatformTenantReferences.values(record));
        return values;
    }
}
