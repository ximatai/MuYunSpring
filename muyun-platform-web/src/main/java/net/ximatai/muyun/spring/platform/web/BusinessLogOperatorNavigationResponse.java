package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Progressive tenant → organization → department navigation for a log operator selector. */
public record BusinessLogOperatorNavigationResponse(
        boolean showTenantNavigation,
        List<BusinessLogOperatorNavigationItemResponse> tenants,
        List<BusinessLogOperatorNavigationItemResponse> organizations,
        List<BusinessLogOperatorNavigationItemResponse> departments
) {
    public BusinessLogOperatorNavigationResponse {
        tenants = tenants == null ? List.of() : List.copyOf(tenants);
        organizations = organizations == null ? List.of() : List.copyOf(organizations);
        departments = departments == null ? List.of() : List.copyOf(departments);
    }
}
