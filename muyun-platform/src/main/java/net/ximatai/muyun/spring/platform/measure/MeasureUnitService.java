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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class MeasureUnitService extends AbstractAbilityService<MeasureUnit> implements
        SoftDeleteAbility<MeasureUnit>,
        EnableAbility<MeasureUnit>,
        SortAbility<MeasureUnit>,
        ReferenceAbility<MeasureUnit>,
        CacheAbility<MeasureUnit>,
        QueryAbility<MeasureUnit>,
        ApplicationReferenceContributor {
    public static final String MODULE_ALIAS = "platform.measure_unit";

    private final MeasureUnitCategoryService categoryService;

    public MeasureUnitService(BaseDao<MeasureUnit, String> unitDao,
                              MeasureUnitCategoryService categoryService) {
        super(MODULE_ALIAS, MeasureUnit.class, unitDao);
        this.categoryService = categoryService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, MeasureUnit.class, java.util.List.of("id", "tenantId", "applicationAlias", "categoryAlias", "code", "symbol", "scale", "factorToBase", "offsetToBase", "roundingMode", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("title"));
    }

    @Override
    public String resourceKey() {
        return "measureUnit";
    }

    @Override
    public String resourceName() {
        return "计量单位";
    }

    @Override
    public boolean hasReferenceTo(String applicationAlias) {
        return findOne(Criteria.of().eq("applicationAlias", applicationAlias)) != null;
    }

    @Override
    public void beforeInsert(MeasureUnit unit) {
        normalizeAndValidate(unit);
    }

    @Override
    public void beforeUpdate(MeasureUnit unit) {
        normalizeAndValidate(unit);
        validateImmutableIdentity(unit);
    }

    @Override
    public net.ximatai.muyun.spring.ability.SortPartition<MeasureUnit> sortPartition() {
        return net.ximatai.muyun.spring.ability.SortPartitions.of(unit -> categoryScope(
                        unit.getApplicationAlias(), unit.getCategoryAlias())
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, unit.getTenantId()),
                net.ximatai.muyun.spring.ability.SortPartitions.byFieldsWithMessage(
                "Measure unit sort can only move records within the same category",
                "tenantId", "applicationAlias", "categoryAlias"));
    }

    @Override
    public List<String> sortPartitionFields() {
        return List.of("tenantId", "applicationAlias", "categoryAlias");
    }

    public MeasureUnit resolveUnit(String applicationAlias, String categoryAlias, String unitCode) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireCategoryAlias(categoryAlias);
        String validUnitCode = requireCode(unitCode, "measureUnitCode");
        return findOne(categoryScope(validApplicationAlias, validCategoryAlias).eq("code", validUnitCode));
    }

    public MeasureUnit requireUnit(String applicationAlias, String categoryAlias, String unitCode) {
        MeasureUnit unit = resolveUnit(applicationAlias, categoryAlias, unitCode);
        if (unit == null) {
            throw new PlatformException("Measure unit requires existing unit: " + unitCode);
        }
        return unit;
    }

    public MeasureUnit requireEnabledUnit(String applicationAlias, String categoryAlias, String unitCode) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireCategoryAlias(categoryAlias);
        categoryService.requireEnabledCategory(validApplicationAlias, validCategoryAlias);
        MeasureUnit unit = resolveUnit(validApplicationAlias, validCategoryAlias, unitCode);
        if (unit == null || !Boolean.TRUE.equals(unit.getEnabled())) {
            throw new PlatformException("Measure unit requires enabled unit: " + unitCode);
        }
        return unit;
    }

    public MeasureUnit resolveVisibleUnit(String applicationAlias, String categoryAlias, String unitCode) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireCategoryAlias(categoryAlias);
        String validUnitCode = requireCode(unitCode, "measureUnitCode");
        MeasureUnitCategory category = categoryService.resolveVisibleCategory(validApplicationAlias, validCategoryAlias);
        if (category == null) {
            return null;
        }
        return resolveUnitInCategoryScope(category, validUnitCode);
    }

    public MeasureUnit requireVisibleUnit(String applicationAlias, String categoryAlias, String unitCode) {
        MeasureUnit unit = resolveVisibleUnit(applicationAlias, categoryAlias, unitCode);
        if (unit == null) {
            throw new PlatformException("Measure unit requires existing visible unit: " + unitCode);
        }
        return unit;
    }

    public MeasureUnit requireEnabledVisibleUnit(String applicationAlias, String categoryAlias, String unitCode) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireCategoryAlias(categoryAlias);
        String validUnitCode = requireCode(unitCode, "measureUnitCode");
        MeasureUnitCategory category = categoryService.requireEnabledVisibleCategory(validApplicationAlias, validCategoryAlias);
        MeasureUnit unit = resolveUnitInCategoryScope(category, validUnitCode);
        if (unit == null || !Boolean.TRUE.equals(unit.getEnabled())) {
            throw new PlatformException("Measure unit requires enabled unit in visible scope: " + unitCode);
        }
        return unit;
    }

    MeasureUnit requireEnabledUnitInCategory(MeasureUnitCategory category, String unitCode) {
        String validUnitCode = requireCode(unitCode, "measureUnitCode");
        MeasureUnit unit = resolveUnitInCategoryScope(category, validUnitCode);
        if (unit == null || !Boolean.TRUE.equals(unit.getEnabled())) {
            throw new PlatformException("Measure unit requires enabled unit: " + unitCode);
        }
        return unit;
    }

    public List<MeasureUnit> listUnits(String applicationAlias, String categoryAlias, boolean enabledOnly) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireCategoryAlias(categoryAlias);
        if (enabledOnly) {
            categoryService.requireEnabledCategory(validApplicationAlias, validCategoryAlias);
        } else {
            categoryService.requireCategory(validApplicationAlias, validCategoryAlias);
        }
        Criteria criteria = categoryScope(validApplicationAlias, validCategoryAlias);
        if (enabledOnly) {
            criteria.eq("enabled", Boolean.TRUE);
        }
        return list(criteria, new PageRequest(0, Integer.MAX_VALUE), Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public List<MeasureUnit> listVisibleUnits(String applicationAlias, String categoryAlias, boolean enabledOnly) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireCategoryAlias(categoryAlias);
        MeasureUnitCategory category = enabledOnly
                ? categoryService.requireEnabledVisibleCategory(validApplicationAlias, validCategoryAlias)
                : categoryService.requireVisibleCategory(validApplicationAlias, validCategoryAlias);
        Criteria criteria = categoryScope(category.getApplicationAlias(), category.getAlias());
        if (enabledOnly) {
            criteria.eq("enabled", Boolean.TRUE);
        }
        return listInCategoryScope(category, criteria);
    }

    private void normalizeAndValidate(MeasureUnit unit) {
        String applicationAlias = PlatformNameRules.requireApplicationAlias(unit.getApplicationAlias());
        String categoryAlias = requireCategoryAlias(unit.getCategoryAlias());
        MeasureUnitCategory category = categoryService.requireCategory(applicationAlias, categoryAlias);
        String code = requireCode(unit.getCode(), "measureUnitCode");
        unit.setApplicationAlias(category.getApplicationAlias());
        unit.setCategoryAlias(category.getAlias());
        unit.setCode(code);
        if (unit.getSymbol() != null && unit.getSymbol().isBlank()) {
            unit.setSymbol(null);
        }
        if (unit.getScale() != null && unit.getScale() < 0) {
            throw new PlatformException("measure unit scale must not be negative: " + unit.getCode());
        }
        if (unit.getFactorToBase() == null) {
            unit.setFactorToBase(BigDecimal.ONE);
        }
        if (unit.getFactorToBase().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PlatformException("measure unit factorToBase must be positive: " + unit.getCode());
        }
        if (unit.getOffsetToBase() == null) {
            unit.setOffsetToBase(BigDecimal.ZERO);
        }
        if (unit.getRoundingMode() == null) {
            unit.setRoundingMode(RoundingMode.HALF_UP);
        }
        rejectDuplicate(unit, categoryScope(unit.getApplicationAlias(), unit.getCategoryAlias())
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, unit.getTenantId())
                        .eq("code", unit.getCode()),
                "measure unit code must be unique within category: " + unit.getCode());
    }

    private void validateImmutableIdentity(MeasureUnit unit) {
        MeasureUnit existing = selectIncludingDeleted(unit.getId());
        rejectChanged(existing, unit, "Measure unit application", MeasureUnit::getApplicationAlias);
        rejectChanged(existing, unit, "Measure unit category", MeasureUnit::getCategoryAlias);
        rejectChanged(existing, unit, "Measure unit code", MeasureUnit::getCode);
    }

    private Criteria categoryScope(String applicationAlias, String categoryAlias) {
        return Criteria.of()
                .eq("applicationAlias", applicationAlias)
                .eq("categoryAlias", categoryAlias);
    }

    private MeasureUnit resolveUnitInCategoryScope(MeasureUnitCategory category, String unitCode) {
        Criteria criteria = categoryScope(category.getApplicationAlias(), category.getAlias()).eq("code", unitCode);
        return listInCategoryScope(category, criteria).stream()
                .filter(unit -> categoryService.sameVisibilityScope(category, unit))
                .findFirst()
                .orElse(null);
    }

    private List<MeasureUnit> listInCategoryScope(MeasureUnitCategory category, Criteria criteria) {
        if (category.getTenantId() == null || category.getTenantId().isBlank()) {
            try (TenantContext.Scope ignored = TenantContext.system("select global measure units")) {
                return list(criteria, new PageRequest(0, Integer.MAX_VALUE), Sort.asc(PlatformAbilityFields.SORT_FIELD))
                        .stream()
                        .filter(unit -> unit.getTenantId() == null || unit.getTenantId().isBlank())
                        .toList();
            }
        }
        return list(criteria.eqNullable(StandardEntitySchema.TENANT_ID_FIELD, category.getTenantId()),
                new PageRequest(0, Integer.MAX_VALUE), Sort.asc(PlatformAbilityFields.SORT_FIELD))
                .stream()
                .filter(unit -> Objects.equals(unit.getTenantId(), category.getTenantId()))
                .toList();
    }

    private String requireCode(String value, String name) {
        return PlatformNameRules.requireCode(value, name);
    }

    private String requireCategoryAlias(String value) {
        return PlatformNameRules.requireIdentifier(value, "measureUnitCategoryAlias");
    }
}
