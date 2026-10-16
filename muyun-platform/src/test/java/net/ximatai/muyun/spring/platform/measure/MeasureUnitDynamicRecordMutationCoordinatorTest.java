package net.ximatai.muyun.spring.platform.measure;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitConversionMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeasureUnitDynamicRecordMutationCoordinatorTest {
    private final MeasureUnitCategoryService categoryService = new MeasureUnitCategoryService(new TestMemoryDao<>());
    private final MeasureUnitService unitService = new MeasureUnitService(new TestMemoryDao<>(), categoryService);
    private final MeasureUnitConversionRuleService ruleService =
            new MeasureUnitConversionRuleService(new TestMemoryDao<>(), unitService);
    private final MeasureUnitDynamicRecordMutationCoordinator coordinator =
            new MeasureUnitDynamicRecordMutationCoordinator(
                    new MeasureUnitConversionService(categoryService, unitService),
                    new MeasureUnitBusinessConversionService(unitService, ruleService),
                    Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneOffset.UTC)
            );

    @Test
    void shouldNormalizeSelectableMeasureUnitBeforeDynamicCreate() {
        packageUnits();
        DynamicRecord record = new DynamicRecord(packageEntity())
                .setValue("quantity", new BigDecimal("2"))
                .setValue("quantityUnit", "box");

        coordinator.beforeCreate("sales.order", "line", record);

        assertThat(baseValue(record)).isEqualByComparingTo("24");
    }

    @Test
    void shouldReuseExistingUnitWhenOnlyMeasureValueChangesOnUpdate() {
        packageUnits();
        DynamicRecord before = new DynamicRecord(packageEntity())
                .setValue("quantity", new BigDecimal("2"))
                .setValue("quantityUnit", "box");
        coordinator.beforeCreate("sales.order", "line", before);
        DynamicRecord incoming = new DynamicRecord(packageEntity())
                .setValue("quantity", new BigDecimal("3"));

        coordinator.beforeUpdate("sales.order", "line", before, incoming);

        assertThat(baseValue(incoming)).isEqualByComparingTo("36");
    }

    @Test
    void shouldReuseExistingValueWhenOnlyMeasureUnitChangesOnUpdate() {
        packageUnits();
        DynamicRecord before = new DynamicRecord(packageEntity())
                .setValue("quantity", new BigDecimal("24"))
                .setValue("quantityUnit", "bottle");
        coordinator.beforeCreate("sales.order", "line", before);
        DynamicRecord incoming = new DynamicRecord(packageEntity())
                .setValue("quantityUnit", "box");

        coordinator.beforeUpdate("sales.order", "line", before, incoming);

        assertThat(baseValue(incoming)).isEqualByComparingTo("288");
    }

    @Test
    void shouldApplyDefaultSelectableUnitBeforeDynamicCreate() {
        packageUnits();
        DynamicRecord record = new DynamicRecord(packageEntityWithDefaultUnit())
                .setValue("quantity", new BigDecimal("2"));

        coordinator.beforeCreate("sales.order", "line", record);

        assertThat(record.getValue("quantityUnit")).isEqualTo("box");
        assertThat(baseValue(record)).isEqualByComparingTo("24");
    }

    @Test
    void shouldBackfillDefaultSelectableUnitWhenUpdatingOldRecordWithoutUnit() {
        packageUnits();
        DynamicRecord before = new DynamicRecord(packageEntityWithDefaultUnit())
                .setValue("quantity", new BigDecimal("1"));
        DynamicRecord incoming = new DynamicRecord(packageEntityWithDefaultUnit())
                .setValue("quantity", new BigDecimal("2"));

        coordinator.beforeUpdate("sales.order", "line", before, incoming);

        assertThat(incoming.getValue("quantityUnit")).isEqualTo("box");
        assertThat(baseValue(incoming)).isEqualByComparingTo("24");
    }

    @Test
    void shouldRejectExplicitlyClearedUnitWhenMeasureValueExists() {
        packageUnits();
        DynamicRecord before = new DynamicRecord(packageEntity())
                .setValue("quantity", new BigDecimal("2"))
                .setValue("quantityUnit", "box");
        DynamicRecord incoming = new DynamicRecord(packageEntity())
                .setValue("quantityUnit", null);

        assertThatThrownBy(() -> coordinator.beforeUpdate("sales.order", "line", before, incoming))
                .hasMessageContaining("quantityUnit");
    }

    @Test
    void shouldAllowMissingOptionalSelectableUnitWhenMeasureValueExists() {
        packageUnits();
        DynamicRecord record = new DynamicRecord(packageOptionalUnitEntity())
                .setValue("quantity", new BigDecimal("2"));

        coordinator.beforeCreate("sales.order", "line", record);

        assertThat(baseValue(record)).isNull();
    }

    @Test
    void shouldClearBaseValueWhenOptionalSelectableUnitIsExplicitlyClearedOnUpdate() {
        packageUnits();
        DynamicRecord before = new DynamicRecord(packageOptionalUnitEntity())
                .setValue("quantity", new BigDecimal("2"))
                .setValue("quantityUnit", "box");
        coordinator.beforeCreate("sales.order", "line", before);
        DynamicRecord incoming = new DynamicRecord(packageOptionalUnitEntity())
                .setValue("quantityUnit", null);

        coordinator.beforeUpdate("sales.order", "line", before, incoming);

        assertThat(baseValue(incoming)).isNull();
    }

    @Test
    void shouldApplyBusinessRuleForCrossCategoryBaseUnit() {
        rollAndLengthUnits();
        MeasureUnitConversionRule rule = new MeasureUnitConversionRule();
        rule.setApplicationAlias("sales");
        rule.setScopeType(MeasureUnitConversionScopeType.GLOBAL);
        rule.setFromCategoryAlias("roll");
        rule.setFromUnitCode("roll");
        rule.setToCategoryAlias("length");
        rule.setToUnitCode("m");
        rule.setFactor(new BigDecimal("30"));
        ruleService.insert(rule);
        DynamicRecord record = new DynamicRecord(rollEntity())
                .setValue("quantity", new BigDecimal("2"));

        coordinator.beforeCreate("sales.order", "line", record);

        assertThat(baseValue(record)).isEqualByComparingTo("60");
    }

    @Test
    void shouldUseInjectedClockZoneForBusinessRuleEffectiveTime() {
        rollAndLengthUnits();
        MeasureUnitConversionRule rule = rule("sales", MeasureUnitConversionScopeType.GLOBAL,
                "roll", "roll", "length", "m", "30");
        rule.setEffectiveFrom(LocalDateTime.of(2026, 6, 13, 0, 0));
        rule.setEffectiveTo(LocalDateTime.of(2026, 6, 13, 1, 0));
        ruleService.insert(rule);
        DynamicRecord record = new DynamicRecord(rollEntity())
                .setValue("quantity", new BigDecimal("2"));

        coordinator.beforeCreate("sales.order", "line", record);

        assertThat(baseValue(record)).isEqualByComparingTo("60");
    }

    @Test
    void shouldNormalizeConfiguredBusinessHardConversionsBeforeDynamicCreate() {
        packageUnits();
        unit("sales", "package", "pallet", BigDecimal.ONE);
        rollAndLengthUnits();
        ruleService.insert(rule("sales", MeasureUnitConversionScopeType.GLOBAL,
                "package", "box", "package", "bottle", "12"));
        ruleService.insert(rule("sales", MeasureUnitConversionScopeType.GLOBAL,
                "package", "pallet", "package", "box", "48"));
        ruleService.insert(rule("sales", MeasureUnitConversionScopeType.GLOBAL,
                "roll", "roll", "length", "m", "30"));
        DynamicRecord palletLine = new DynamicRecord(packageBusinessRuleEntity())
                .setValue("quantity", new BigDecimal("2"))
                .setValue("quantityUnit", "pallet");
        DynamicRecord rollLine = new DynamicRecord(rollEntity())
                .setValue("quantity", new BigDecimal("2"));

        coordinator.beforeCreate("sales.order", "line", palletLine);
        coordinator.beforeCreate("sales.order", "line", rollLine);

        assertThat(baseValue(palletLine)).isEqualByComparingTo("1152");
        assertThat(baseValue(rollLine)).isEqualByComparingTo("60");
    }

    @Test
    void shouldReuseExistingConversionScopeWhenOnlyMeasureValueChangesOnUpdate() {
        rollAndLengthUnits();
        MeasureUnitConversionRule rule = new MeasureUnitConversionRule();
        rule.setApplicationAlias("sales");
        rule.setScopeType(MeasureUnitConversionScopeType.RECORD_CONTEXT);
        rule.setModuleAlias("sales.order");
        rule.setContextObjectType("sku_id");
        rule.setContextObjectId("sku-1");
        rule.setFromCategoryAlias("roll");
        rule.setFromUnitCode("roll");
        rule.setToCategoryAlias("length");
        rule.setToUnitCode("m");
        rule.setFactor(new BigDecimal("30"));
        ruleService.insert(rule);
        DynamicRecord before = new DynamicRecord(rollEntityWithScope())
                .setValue("quantity", new BigDecimal("1"))
                .setValue("skuId", "sku-1");
        DynamicRecord incoming = new DynamicRecord(rollEntityWithScope())
                .setValue("quantity", new BigDecimal("3"));

        coordinator.beforeUpdate("sales.order", "line", before, incoming);

        assertThat(baseValue(incoming)).isEqualByComparingTo("90");
    }

    @Test
    void shouldReportMeasureFieldContextWhenConversionFails() {
        packageUnits();
        DynamicRecord record = new DynamicRecord(packageEntity())
                .setValue("quantity", new BigDecimal("2"))
                .setValue("quantityUnit", "pallet");

        assertThatThrownBy(() -> coordinator.beforeCreate("sales.order", "line", record))
                .hasMessageContaining("measure unit normalization failed")
                .hasMessageContaining("module=sales.order")
                .hasMessageContaining("entity=line")
                .hasMessageContaining("field=quantity")
                .hasMessageContaining("Measure unit requires enabled unit")
                .isInstanceOfSatisfying(PlatformException.class, error -> {
                    var cause = (PlatformException) error.getCause();
                    assertThat(error.textSource()).isNotNull().isEqualTo(cause.textSource());
                    assertThat(error.messageArgs()).isEqualTo(cause.messageArgs());
                    var catalog = net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
                    var texts = net.ximatai.muyun.spring.common.i18n.TextResources.load(getClass().getClassLoader(), catalog);
                    assertThat(texts.resolve(error.textSource(), error.messageArgs(), "en").text()).startsWith("Measure unit requires enabled unit");
                    assertThat(texts.resolve(error.textSource(), error.messageArgs(), "zh-CN").text()).startsWith("计量单位未启用");
                    assertThat(error.httpStatus()).isEqualTo(400);
                    assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
                })
                .cause()
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void shouldReportMeasureChildEntityContextWhenRelationChildConversionFails() {
        packageUnits();
        DynamicRecord parent = new DynamicRecord(packageEntity());
        DynamicRecord child = new DynamicRecord(packageEntity())
                .setValue("quantity", new BigDecimal("2"))
                .setValue("quantityUnit", "pallet");

        assertThatThrownBy(() -> coordinator.beforeRelationChildCreate("sales.order", "order", "lines", "line",
                parent, child))
                .hasMessageContaining("measure unit normalization failed")
                .hasMessageContaining("module=sales.order")
                .hasMessageContaining("entity=line")
                .hasMessageContaining("field=quantity")
                .cause()
                .isInstanceOf(PlatformException.class);
    }

    private EntityDefinition packageEntity() {
        return packageEntity(null);
    }

    private EntityDefinition packageEntityWithDefaultUnit() {
        return packageEntity("box");
    }

    private EntityDefinition packageOptionalUnitEntity() {
        return new EntityDefinition(
                "line",
                "sales_order_line",
                "Line",
                List.of(
                        FieldDefinition.decimal("quantity", "Quantity").measureUnit(new FieldMeasureUnitDefinition(
                                "package",
                                FieldMeasureUnitMode.SELECTABLE,
                                null,
                                null,
                                "quantityUnit",
                                "quantityBase",
                                "package",
                                "bottle",
                                FieldMeasureUnitConversionMode.LINEAR,
                                null,
                                false
                        )),
                        FieldDefinition.string("quantityUnit", "Unit").column("quantity_unit").length(64),
                        FieldDefinition.decimal("quantityBase", "Base Quantity").column("quantity_base")
                ),
                Set.of(EntityCapability.CRUD)
        );
    }

    private EntityDefinition packageBusinessRuleEntity() {
        return new EntityDefinition(
                "line",
                "sales_order_line",
                "Line",
                List.of(
                        FieldDefinition.decimal("quantity", "Quantity").measureUnit(new FieldMeasureUnitDefinition(
                                "package",
                                FieldMeasureUnitMode.SELECTABLE,
                                null,
                                null,
                                "quantityUnit",
                                "quantityBase",
                                "package",
                                "bottle",
                                FieldMeasureUnitConversionMode.BUSINESS_RULE,
                                null,
                                true
                        )),
                        FieldDefinition.string("quantityUnit", "Unit").column("quantity_unit").length(64),
                        FieldDefinition.decimal("quantityBase", "Base Quantity").column("quantity_base")
                ),
                Set.of(EntityCapability.CRUD)
        );
    }

    private EntityDefinition packageEntity(String defaultUnitCode) {
        return new EntityDefinition(
                "line",
                "sales_order_line",
                "Line",
                List.of(
                        FieldDefinition.decimal("quantity", "Quantity").measureUnit(new FieldMeasureUnitDefinition(
                                "package",
                                FieldMeasureUnitMode.SELECTABLE,
                                null,
                                defaultUnitCode,
                                "quantityUnit",
                                "quantityBase",
                                "package",
                                "bottle",
                                FieldMeasureUnitConversionMode.LINEAR,
                                null,
                                true
                        )),
                        FieldDefinition.string("quantityUnit", "Unit").column("quantity_unit").length(64),
                        FieldDefinition.decimal("quantityBase", "Base Quantity").column("quantity_base")
                ),
                Set.of(EntityCapability.CRUD)
        );
    }

    private EntityDefinition rollEntity() {
        return rollEntity(null);
    }

    private EntityDefinition rollEntityWithScope() {
        return rollEntity("skuId");
    }

    private EntityDefinition rollEntity(String conversionScopeFieldName) {
        List<FieldDefinition> fields = new java.util.ArrayList<>(List.of(
                FieldDefinition.decimal("quantity", "Quantity").measureUnit(new FieldMeasureUnitDefinition(
                        "roll",
                        FieldMeasureUnitMode.FIXED,
                        "roll",
                        "roll",
                        null,
                        "quantityBase",
                        "length",
                        "m",
                        FieldMeasureUnitConversionMode.BUSINESS_RULE,
                        conversionScopeFieldName,
                        false
                )),
                FieldDefinition.decimal("quantityBase", "Base Quantity").column("quantity_base")
        ));
        if (conversionScopeFieldName != null) {
            fields.add(FieldDefinition.string(conversionScopeFieldName, "SKU").column("sku_id").length(64));
        }
        return new EntityDefinition(
                "line",
                "sales_order_line",
                "Line",
                fields,
                Set.of(EntityCapability.CRUD)
        );
    }

    private void packageUnits() {
        category("sales", "package", "bottle", MeasureDimension.COUNT);
        unit("sales", "package", "bottle", BigDecimal.ONE);
        unit("sales", "package", "box", new BigDecimal("12"));
    }

    private void rollAndLengthUnits() {
        category("sales", "roll", "roll", MeasureDimension.COUNT);
        unit("sales", "roll", "roll", BigDecimal.ONE);
        category("sales", "length", "m", MeasureDimension.LENGTH);
        unit("sales", "length", "m", BigDecimal.ONE);
    }

    private void category(String applicationAlias, String alias, String baseUnitCode, MeasureDimension dimension) {
        MeasureUnitCategory category = new MeasureUnitCategory();
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        category.setDimension(dimension);
        category.setBaseUnitCode(baseUnitCode);
        categoryService.insert(category);
    }

    private void unit(String applicationAlias, String categoryAlias, String code, BigDecimal factorToBase) {
        MeasureUnit unit = new MeasureUnit();
        unit.setApplicationAlias(applicationAlias);
        unit.setCategoryAlias(categoryAlias);
        unit.setCode(code);
        unit.setTitle(code);
        unit.setFactorToBase(factorToBase);
        unitService.insert(unit);
    }

    private MeasureUnitConversionRule rule(String applicationAlias,
                                           MeasureUnitConversionScopeType scopeType,
                                           String fromCategoryAlias,
                                           String fromUnitCode,
                                           String toCategoryAlias,
                                           String toUnitCode,
                                           String factor) {
        MeasureUnitConversionRule rule = new MeasureUnitConversionRule();
        rule.setApplicationAlias(applicationAlias);
        rule.setScopeType(scopeType);
        rule.setFromCategoryAlias(fromCategoryAlias);
        rule.setFromUnitCode(fromUnitCode);
        rule.setToCategoryAlias(toCategoryAlias);
        rule.setToUnitCode(toUnitCode);
        rule.setFactor(new BigDecimal(factor));
        return rule;
    }

    private BigDecimal baseValue(DynamicRecord record) {
        return (BigDecimal) record.getValue("quantityBase");
    }
}
