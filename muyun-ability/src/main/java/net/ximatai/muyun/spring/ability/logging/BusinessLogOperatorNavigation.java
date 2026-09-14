package net.ximatai.muyun.spring.ability.logging;

import java.util.List;

/** Distinct navigation facts derived from the same authorized, append-only log stream as candidates. */
public record BusinessLogOperatorNavigation(
        List<BusinessLogOperatorNavigationItem> tenants,
        List<BusinessLogOperatorNavigationItem> organizations,
        List<BusinessLogOperatorNavigationItem> departments
) {
    public BusinessLogOperatorNavigation {
        tenants = tenants == null ? List.of() : List.copyOf(tenants);
        organizations = organizations == null ? List.of() : List.copyOf(organizations);
        departments = departments == null ? List.of() : List.copyOf(departments);
    }
}
