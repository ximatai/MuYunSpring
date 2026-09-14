package net.ximatai.muyun.spring.ability.logging;

import java.util.Map;

/** Optional directory labels for already-authorized event-time navigation values. */
public record BusinessLogOperatorNavigationLabels(
        Map<BusinessLogOperatorNavigationItem, String> tenants,
        Map<BusinessLogOperatorNavigationItem, String> organizations,
        Map<BusinessLogOperatorNavigationItem, String> departments
) {
    public BusinessLogOperatorNavigationLabels {
        tenants = tenants == null ? Map.of() : Map.copyOf(tenants);
        organizations = organizations == null ? Map.of() : Map.copyOf(organizations);
        departments = departments == null ? Map.of() : Map.copyOf(departments);
    }

    public static final BusinessLogOperatorNavigationLabels EMPTY =
            new BusinessLogOperatorNavigationLabels(Map.of(), Map.of(), Map.of());
}
