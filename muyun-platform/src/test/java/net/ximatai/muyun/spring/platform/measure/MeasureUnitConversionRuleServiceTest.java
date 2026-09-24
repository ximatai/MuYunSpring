package net.ximatai.muyun.spring.platform.measure;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeasureUnitConversionRuleServiceTest {
    private final TestMemoryDao<MeasureUnitCategory> categoryDao = new TestMemoryDao<>();
    private final TestMemoryDao<MeasureUnit> unitDao = new TestMemoryDao<>();
    private final TestMemoryDao<MeasureUnitConversionRule> ruleDao = new TestMemoryDao<>();
    private final MeasureUnitCategoryService categoryService = new MeasureUnitCategoryService(categoryDao);
    private final MeasureUnitService unitService = new MeasureUnitService(unitDao, categoryService);
    private final MeasureUnitConversionRuleService ruleService =
            new MeasureUnitConversionRuleService(ruleDao, unitService);
    private final MeasureUnitBusinessConversionService conversionService =
            new MeasureUnitBusinessConversionService(unitService, ruleService);

    @Test
    void shouldOnlyUseGlobalRulesWithoutTenantContext() {
        prepareQuantityUnits();
        String global = ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12"));
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            prepareQuantityUnits();
            ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                    null, null, null, "quantity", "box", "quantity", "bottle", "10"));
        }
        assertThat(ruleService.applicableRules(context("crm", null, null, null)))
                .extracting(MeasureUnitConversionRule::getId).containsExactly(global);
        assertThat(categoryService.listVisibleCategories("crm", false))
                .allSatisfy(category -> assertThat(category.getTenantId()).isNull());
    }

    @Test
    void shouldConvertByChainedGlobalRules() {
        prepareQuantityUnits();
        String boxRuleId = ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12"));
        String palletRuleId = ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "pallet", "quantity", "box", "48"));

        MeasureUnitBusinessConversion conversion = conversionService.convert(context("crm", null, null, null),
                new BigDecimal("2"), "quantity", "pallet", "quantity", "bottle");

        assertThat(conversion.convertedValue()).isEqualByComparingTo("1152");
        assertThat(conversion.ruleIds()).containsExactly(palletRuleId, boxRuleId);
    }

    @Test
    void shouldUseRecordContextRuleBeforeModuleOrGlobalRuleForSameEdge() {
        prepareQuantityUnits();
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12"));
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.MODULE,
                "crm.order", null, null, "quantity", "pallet", "quantity", "box", "48"));
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.RECORD_CONTEXT,
                "crm.order", "sku", "sku-1", "quantity", "pallet", "quantity", "box", "50"));

        MeasureUnitBusinessConversion conversion = conversionService.convert(context("crm", "crm.order", "sku", "sku-1"),
                BigDecimal.ONE, "quantity", "pallet", "quantity", "bottle");

        assertThat(conversion.convertedValue()).isEqualByComparingTo("600");
    }

    @Test
    void shouldPreferMoreSpecificChainOverGlobalDirectRule() {
        prepareQuantityUnits();
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "pallet", "quantity", "bottle", "500"));
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12"));
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.RECORD_CONTEXT,
                "crm.order", "sku", "sku-1", "quantity", "pallet", "quantity", "box", "50"));

        MeasureUnitBusinessConversion conversion = conversionService.convert(context("crm", "crm.order", "sku", "sku-1"),
                BigDecimal.ONE, "quantity", "pallet", "quantity", "bottle");

        assertThat(conversion.convertedValue()).isEqualByComparingTo("600");
    }

    @Test
    void shouldSupportCrossCategoryHardConversion() {
        prepareQuantityUnits();
        prepareLengthUnits();
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "roll", "length", "m", "30"));

        MeasureUnitBusinessConversion conversion = conversionService.convert(context("crm", null, null, null),
                new BigDecimal("3"), "quantity", "roll", "length", "m");

        assertThat(conversion.convertedValue()).isEqualByComparingTo("90");
    }

    @Test
    void shouldPreferTenantSharedBusinessRuleOverGlobalSharedRule() {
        prepareSharedQuantityUnits();
        ruleService.insert(rule(MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS, MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12"));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            prepareSharedQuantityUnits();
            ruleService.insert(rule(MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS, MeasureUnitConversionScopeType.GLOBAL,
                    null, null, null, "quantity", "box", "quantity", "bottle", "10"));

            MeasureUnitBusinessConversion conversion = conversionService.convert(context("crm", null, null, null),
                    new BigDecimal("2"), "quantity", "box", "quantity", "bottle");

            assertThat(conversion.convertedValue()).isEqualByComparingTo("20");
        }
    }

    @Test
    void shouldPreferSharedBusinessRuleOverApplicationCompatibleRuleForSameEdge() {
        prepareSharedQuantityUnits();
        prepareQuantityUnits();
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "8"));
        ruleService.insert(rule(MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS, MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "10"));

        MeasureUnitBusinessConversion conversion = conversionService.convert(context("crm", null, null, null),
                new BigDecimal("2"), "quantity", "box", "quantity", "bottle");

        assertThat(conversion.convertedValue()).isEqualByComparingTo("20");
    }

    @Test
    void shouldSupportReverseConversionFromSameRule() {
        prepareQuantityUnits();
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12"));

        MeasureUnitBusinessConversion conversion = conversionService.convert(context("crm", null, null, null),
                new BigDecimal("24"), "quantity", "bottle", "quantity", "box");

        assertThat(conversion.convertedValue()).isEqualByComparingTo("2");
    }

    @Test
    void shouldRejectChainThroughDisabledIntermediateUnit() {
        prepareQuantityUnits();
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "pallet", "quantity", "box", "48"));
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12"));
        unitService.disable(unitService.resolveUnit("crm", "quantity", "box").getId());

        assertThatThrownBy(() -> conversionService.convert(context("crm", null, null, null),
                BigDecimal.ONE, "quantity", "pallet", "quantity", "bottle"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("enabled unit");
    }

    @Test
    void shouldRejectDuplicateRuleInSameScope() {
        prepareQuantityUnits();
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12"));

        assertThatThrownBy(() -> ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                        null, null, null, "quantity", "box", "quantity", "bottle", "10")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void shouldRejectReverseRuleInOverlappingEffectiveWindow() {
        prepareQuantityUnits();
        ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12"));

        assertThatThrownBy(() -> ruleService.insert(rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                        null, null, null, "quantity", "bottle", "quantity", "box", "0.1")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reverse");
    }

    @Test
    void shouldAllowSameEdgeRulesWhenEffectiveWindowsDoNotOverlap() {
        prepareQuantityUnits();
        MeasureUnitConversionRule first = rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12");
        first.setEffectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        first.setEffectiveTo(LocalDateTime.of(2027, 1, 1, 0, 0));
        ruleService.insert(first);
        MeasureUnitConversionRule second = rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "10");
        second.setEffectiveFrom(LocalDateTime.of(2027, 1, 1, 0, 0));
        second.setEffectiveTo(LocalDateTime.of(2028, 1, 1, 0, 0));
        ruleService.insert(second);

        MeasureUnitBusinessConversion oldRule = conversionService.convert(
                context("crm", null, null, null, LocalDateTime.of(2026, 6, 1, 0, 0)),
                BigDecimal.ONE, "quantity", "box", "quantity", "bottle");
        MeasureUnitBusinessConversion newRule = conversionService.convert(
                context("crm", null, null, null, LocalDateTime.of(2027, 6, 1, 0, 0)),
                BigDecimal.ONE, "quantity", "box", "quantity", "bottle");

        assertThat(oldRule.convertedValue()).isEqualByComparingTo("12");
        assertThat(newRule.convertedValue()).isEqualByComparingTo("10");
    }

    @Test
    void shouldRespectRuleEffectiveTime() {
        prepareQuantityUnits();
        MeasureUnitConversionRule rule = rule("crm", MeasureUnitConversionScopeType.GLOBAL,
                null, null, null, "quantity", "box", "quantity", "bottle", "12");
        rule.setEffectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        rule.setEffectiveTo(LocalDateTime.of(2027, 1, 1, 0, 0));
        ruleService.insert(rule);

        assertThatThrownBy(() -> conversionService.convert(
                context("crm", null, null, null, LocalDateTime.of(2025, 12, 31, 23, 0)),
                BigDecimal.ONE, "quantity", "box", "quantity", "bottle"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not found");

        MeasureUnitBusinessConversion conversion = conversionService.convert(
                context("crm", null, null, null, LocalDateTime.of(2026, 6, 1, 0, 0)),
                BigDecimal.ONE, "quantity", "box", "quantity", "bottle");
        assertThat(conversion.convertedValue()).isEqualByComparingTo("12");
    }

    @Test
    void shouldRequireContextForRecordContextRuleScope() {
        prepareQuantityUnits();
        MeasureUnitConversionRule invalid = rule("crm", MeasureUnitConversionScopeType.RECORD_CONTEXT,
                "crm.order", "sku", null, "quantity", "box", "quantity", "bottle", "12");

        assertThatThrownBy(() -> ruleService.insert(invalid))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("contextObjectId");
    }

    private void prepareQuantityUnits() {
        categoryService.insert(category("crm", "quantity", MeasureDimension.COUNT, "bottle"));
        unitService.insert(unit("crm", "quantity", "bottle"));
        unitService.insert(unit("crm", "quantity", "box"));
        unitService.insert(unit("crm", "quantity", "pallet"));
        unitService.insert(unit("crm", "quantity", "roll"));
    }

    private void prepareLengthUnits() {
        categoryService.insert(category("crm", "length", MeasureDimension.LENGTH, "m"));
        unitService.insert(unit("crm", "length", "m"));
    }

    private void prepareSharedQuantityUnits() {
        categoryService.insert(category(MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS,
                "quantity", MeasureDimension.COUNT, "bottle"));
        unitService.insert(unit(MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS, "quantity", "bottle"));
        unitService.insert(unit(MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS, "quantity", "box"));
    }

    private MeasureUnitCategory category(String applicationAlias,
                                         String alias,
                                         MeasureDimension dimension,
                                         String baseUnitCode) {
        MeasureUnitCategory category = new MeasureUnitCategory();
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        category.setDimension(dimension);
        category.setBaseUnitCode(baseUnitCode);
        category.setTitle(alias);
        return category;
    }

    private MeasureUnit unit(String applicationAlias, String categoryAlias, String code) {
        MeasureUnit unit = new MeasureUnit();
        unit.setApplicationAlias(applicationAlias);
        unit.setCategoryAlias(categoryAlias);
        unit.setCode(code);
        unit.setTitle(code);
        unit.setFactorToBase(BigDecimal.ONE);
        unit.setOffsetToBase(BigDecimal.ZERO);
        return unit;
    }

    private MeasureUnitConversionRule rule(String applicationAlias,
                                           MeasureUnitConversionScopeType scopeType,
                                           String moduleAlias,
                                           String contextObjectType,
                                           String contextObjectId,
                                           String fromCategoryAlias,
                                           String fromUnitCode,
                                           String toCategoryAlias,
                                           String toUnitCode,
                                           String factor) {
        MeasureUnitConversionRule rule = new MeasureUnitConversionRule();
        rule.setApplicationAlias(applicationAlias);
        rule.setScopeType(scopeType);
        rule.setModuleAlias(moduleAlias);
        rule.setContextObjectType(contextObjectType);
        rule.setContextObjectId(contextObjectId);
        rule.setFromCategoryAlias(fromCategoryAlias);
        rule.setFromUnitCode(fromUnitCode);
        rule.setToCategoryAlias(toCategoryAlias);
        rule.setToUnitCode(toUnitCode);
        rule.setFactor(new BigDecimal(factor));
        rule.setTitle(fromUnitCode + " to " + toUnitCode);
        return rule;
    }

    private MeasureUnitConversionContext context(String applicationAlias,
                                                 String moduleAlias,
                                                 String contextObjectType,
                                                 String contextObjectId) {
        return context(applicationAlias, moduleAlias, contextObjectType, contextObjectId, null);
    }

    private MeasureUnitConversionContext context(String applicationAlias,
                                                 String moduleAlias,
                                                 String contextObjectType,
                                                 String contextObjectId,
                                                 LocalDateTime operatedAt) {
        return new MeasureUnitConversionContext(applicationAlias, moduleAlias,
                contextObjectType, contextObjectId, operatedAt);
    }
}
