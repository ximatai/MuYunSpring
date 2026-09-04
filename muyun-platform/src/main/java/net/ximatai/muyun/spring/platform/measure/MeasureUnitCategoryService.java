package net.ximatai.muyun.spring.platform.measure;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.application.ApplicationReferenceContributor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class MeasureUnitCategoryService extends AbstractAbilityService<MeasureUnitCategory> implements
        SoftDeleteAbility<MeasureUnitCategory>,
        EnableAbility<MeasureUnitCategory>,
        SortAbility<MeasureUnitCategory>,
        ReferenceAbility<MeasureUnitCategory>,
        CacheAbility<MeasureUnitCategory>,
        QueryAbility<MeasureUnitCategory>,
        ApplicationReferenceContributor {
    public static final String MODULE_ALIAS = "platform.measure_unit_category";
    public static final String SHARED_APPLICATION_ALIAS = "platform";

    public MeasureUnitCategoryService(BaseDao<MeasureUnitCategory, String> categoryDao) {
        super(MODULE_ALIAS, MeasureUnitCategory.class, categoryDao);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, MeasureUnitCategory.class, java.util.List.of("id", "tenantId", "applicationAlias", "alias", "dimension", "baseUnitCode", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("title"));
    }

    @Override
    public String resourceKey() {
        return "measureUnitCategory";
    }

    @Override
    public String resourceName() {
        return "计量单位类目";
    }

    @Override
    public boolean hasReferenceTo(String applicationAlias) {
        return findOne(Criteria.of().eq("applicationAlias", applicationAlias)) != null;
    }

    @Override
    public void beforeInsert(MeasureUnitCategory category) {
        normalizeAndValidate(category);
    }

    @Override
    public void beforeUpdate(MeasureUnitCategory category) {
        normalizeAndValidate(category);
        validateImmutableIdentity(category);
    }

    @Override
    public net.ximatai.muyun.spring.ability.SortPartition<MeasureUnitCategory> sortPartition() {
        return net.ximatai.muyun.spring.ability.SortPartitions.of(category -> Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, category.getTenantId())
                        .eq("applicationAlias", category.getApplicationAlias()),
                net.ximatai.muyun.spring.ability.SortPartitions.byFieldsWithMessage(
                "Measure unit category sort can only move records within the same tenant and application",
                "tenantId", "applicationAlias"));
    }

    @Override
    public List<String> sortPartitionFields() {
        return List.of("tenantId", "applicationAlias");
    }

    public MeasureUnitCategory requireCategory(String applicationAlias, String categoryAlias) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireAlias(categoryAlias);
        MeasureUnitCategory category = findOne(Criteria.of()
                .eq("applicationAlias", validApplicationAlias)
                .eq("alias", validCategoryAlias));
        if (category == null) {
            throw new PlatformException("Measure unit category requires existing category: " + validCategoryAlias);
        }
        return category;
    }

    public MeasureUnitCategory requireEnabledCategory(String applicationAlias, String categoryAlias) {
        MeasureUnitCategory category = requireCategory(applicationAlias, categoryAlias);
        if (!Boolean.TRUE.equals(category.getEnabled())) {
            throw new PlatformException("Measure unit category is disabled: " + categoryAlias);
        }
        return category;
    }

    public MeasureUnitCategory resolveVisibleCategory(String applicationAlias, String categoryAlias) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireAlias(categoryAlias);
        for (MeasureUnitCategory category : visibleCategoryCandidates(validApplicationAlias, validCategoryAlias, false)) {
            return category;
        }
        return null;
    }

    public MeasureUnitCategory requireVisibleCategory(String applicationAlias, String categoryAlias) {
        MeasureUnitCategory category = resolveVisibleCategory(applicationAlias, categoryAlias);
        if (category == null) {
            throw new PlatformException("Measure unit category requires existing visible category: " + categoryAlias);
        }
        return category;
    }

    public MeasureUnitCategory requireEnabledVisibleCategory(String applicationAlias, String categoryAlias) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireAlias(categoryAlias);
        for (MeasureUnitCategory category : visibleCategoryCandidates(validApplicationAlias, validCategoryAlias, true)) {
            return category;
        }
        throw new PlatformException("Measure unit category requires enabled visible category: " + categoryAlias);
    }

    public List<MeasureUnitCategory> listCategories(String applicationAlias, boolean enabledOnly) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        Criteria criteria = Criteria.of().eq("applicationAlias", validApplicationAlias);
        if (enabledOnly) {
            criteria.eq("enabled", Boolean.TRUE);
        }
        return list(criteria, new PageRequest(0, Integer.MAX_VALUE), Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public List<MeasureUnitCategory> listVisibleCategories(String applicationAlias, boolean enabledOnly) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        Map<String, MeasureUnitCategory> categories = new LinkedHashMap<>();
        if (TenantContext.currentTenantId().isPresent()) {
            for (String candidateApplication : applicationCandidates(validApplicationAlias)) {
                listCategoryLayer(candidateApplication, enabledOnly)
                        .forEach(category -> categories.putIfAbsent(category.getAlias(), category));
            }
            for (String candidateApplication : applicationCandidates(validApplicationAlias)) {
                listGlobalCategories(categoryCriteria(candidateApplication, enabledOnly))
                        .forEach(category -> categories.putIfAbsent(category.getAlias(), category));
            }
        } else {
            for (String candidateApplication : applicationCandidates(validApplicationAlias)) {
                listCategoryLayer(candidateApplication, enabledOnly)
                        .forEach(category -> categories.putIfAbsent(category.getAlias(), category));
            }
        }
        return List.copyOf(categories.values());
    }

    boolean sameVisibilityScope(MeasureUnitCategory category, MeasureUnit unit) {
        return category != null
                && unit != null
                && Objects.equals(category.getTenantId(), unit.getTenantId())
                && Objects.equals(category.getApplicationAlias(), unit.getApplicationAlias());
    }

    private List<MeasureUnitCategory> visibleCategoryCandidates(String applicationAlias,
                                                               String categoryAlias,
                                                               boolean enabledOnly) {
        return listVisibleCategories(applicationAlias, enabledOnly).stream()
                .filter(category -> Objects.equals(category.getAlias(), categoryAlias))
                .toList();
    }

    private List<MeasureUnitCategory> listCategoryLayer(String applicationAlias, boolean enabledOnly) {
        Criteria criteria = categoryCriteria(applicationAlias, enabledOnly);
        return list(criteria, new PageRequest(0, Integer.MAX_VALUE), Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    private Criteria categoryCriteria(String applicationAlias, boolean enabledOnly) {
        Criteria criteria = Criteria.of().eq("applicationAlias", applicationAlias);
        if (enabledOnly) {
            criteria.eq("enabled", Boolean.TRUE);
        }
        return criteria;
    }

    private List<MeasureUnitCategory> listGlobalCategories(Criteria criteria) {
        try (TenantContext.Scope ignored = TenantContext.system("select global measure unit categories")) {
            return list(criteria, new PageRequest(0, Integer.MAX_VALUE), Sort.asc(PlatformAbilityFields.SORT_FIELD))
                    .stream()
                    .filter(category -> category.getTenantId() == null || category.getTenantId().isBlank())
                    .toList();
        }
    }

    private List<String> applicationCandidates(String applicationAlias) {
        if (SHARED_APPLICATION_ALIAS.equals(applicationAlias)) {
            return List.of(SHARED_APPLICATION_ALIAS);
        }
        return List.of(SHARED_APPLICATION_ALIAS, applicationAlias);
    }

    private void normalizeAndValidate(MeasureUnitCategory category) {
        String applicationAlias = PlatformNameRules.requireApplicationAlias(category.getApplicationAlias());
        String alias = requireAlias(category.getAlias());
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        if (category.getDimension() == null) {
            category.setDimension(MeasureDimension.CUSTOM);
        }
        if (category.getBaseUnitCode() != null && !category.getBaseUnitCode().isBlank()) {
            category.setBaseUnitCode(requireCode(category.getBaseUnitCode(), "baseUnitCode"));
        } else {
            category.setBaseUnitCode(null);
        }
        rejectDuplicate(category, Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, category.getTenantId())
                        .eq("applicationAlias", category.getApplicationAlias())
                        .eq("alias", category.getAlias()),
                "measureUnitCategoryAlias must be unique within application: " + category.getAlias());
    }

    private void validateImmutableIdentity(MeasureUnitCategory category) {
        MeasureUnitCategory existing = selectIncludingDeleted(category.getId());
        rejectChanged(existing, category, "Measure unit category application", MeasureUnitCategory::getApplicationAlias);
        rejectChanged(existing, category, "Measure unit category alias", MeasureUnitCategory::getAlias);
        rejectChanged(existing, category, "Measure unit category dimension", MeasureUnitCategory::getDimension);
        rejectChanged(existing, category, "Measure unit category base unit", MeasureUnitCategory::getBaseUnitCode);
    }

    private String requireAlias(String alias) {
        return PlatformNameRules.requireIdentifier(alias, "measureUnitCategoryAlias");
    }

    private String requireCode(String value, String name) {
        return PlatformNameRules.requireCode(value, name);
    }
}
