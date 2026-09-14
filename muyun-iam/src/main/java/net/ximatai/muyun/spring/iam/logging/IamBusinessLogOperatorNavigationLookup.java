package net.ximatai.muyun.spring.iam.logging;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorNavigation;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorNavigationItem;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorNavigationLabels;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorNavigationLookup;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.department.DepartmentDao;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationDao;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantDao;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded label enrichment for IDs already obtained from an authorized log stream. */
@Service
public class IamBusinessLogOperatorNavigationLookup implements BusinessLogOperatorNavigationLookup {
    private final TenantDao tenantDao;
    private final OrganizationDao organizationDao;
    private final DepartmentDao departmentDao;

    public IamBusinessLogOperatorNavigationLookup(TenantDao tenantDao, OrganizationDao organizationDao,
                                                  DepartmentDao departmentDao) {
        this.tenantDao = tenantDao;
        this.organizationDao = organizationDao;
        this.departmentDao = departmentDao;
    }

    @Override
    public BusinessLogOperatorNavigationLabels resolve(BusinessLogOperatorNavigation navigation) {
        if (navigation == null) {
            return BusinessLogOperatorNavigationLabels.EMPTY;
        }
        return new BusinessLogOperatorNavigationLabels(tenantLabels(navigation.tenants()),
                organizationLabels(navigation.organizations()), departmentLabels(navigation.departments()));
    }

    private Map<BusinessLogOperatorNavigationItem, String> tenantLabels(Collection<BusinessLogOperatorNavigationItem> items) {
        List<String> ids = items.stream().map(BusinessLogOperatorNavigationItem::id).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, String> names = new LinkedHashMap<>();
        tenantDao.query(Criteria.of().in("id", ids), new PageRequest(0, ids.size()))
                .forEach(tenant -> names.put(tenant.getId(), tenant.getTitle()));
        return labels(items, item -> names.get(item.id()));
    }

    private Map<BusinessLogOperatorNavigationItem, String> organizationLabels(Collection<BusinessLogOperatorNavigationItem> items) {
        return labelsByTenant(items, organizationDao, Organization::getTitle);
    }

    private Map<BusinessLogOperatorNavigationItem, String> departmentLabels(Collection<BusinessLogOperatorNavigationItem> items) {
        return labelsByTenant(items, departmentDao, Department::getTitle);
    }

    private <T extends net.ximatai.muyun.spring.common.model.contract.EntityContract>
    Map<BusinessLogOperatorNavigationItem, String> labelsByTenant(Collection<BusinessLogOperatorNavigationItem> items,
            net.ximatai.muyun.spring.ability.BaseDao<T, String> dao, java.util.function.Function<T, String> title) {
        Map<BusinessLogOperatorNavigationItem, String> output = new LinkedHashMap<>();
        items.stream().filter(item -> item.tenantId() != null).collect(java.util.stream.Collectors.groupingBy(
                BusinessLogOperatorNavigationItem::tenantId, LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .forEach((tenantId, group) -> {
                    List<String> ids = group.stream().map(BusinessLogOperatorNavigationItem::id).distinct().toList();
                    Map<String, String> names = new LinkedHashMap<>();
                    dao.query(Criteria.of().eq("tenantId", tenantId).in("id", ids), new PageRequest(0, ids.size()))
                            .forEach(item -> names.put(item.getId(), title.apply(item)));
                    output.putAll(labels(group, item -> names.get(item.id())));
                });
        return Map.copyOf(output);
    }

    private static Map<BusinessLogOperatorNavigationItem, String> labels(Collection<BusinessLogOperatorNavigationItem> items,
                                                                            java.util.function.Function<BusinessLogOperatorNavigationItem, String> label) {
        Map<BusinessLogOperatorNavigationItem, String> output = new LinkedHashMap<>();
        items.forEach(item -> {
            String value = label.apply(item);
            if (value != null && !value.isBlank()) output.put(item, value);
        });
        return Map.copyOf(output);
    }
}
