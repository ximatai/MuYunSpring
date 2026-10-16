package net.ximatai.muyun.spring.platform.measure;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;

@Service
public class MeasureUnitConversionService {
    private final MeasureUnitCategoryService categoryService;
    private final MeasureUnitService unitService;

    public MeasureUnitConversionService(MeasureUnitCategoryService categoryService,
                                        MeasureUnitService unitService) {
        this.categoryService = categoryService;
        this.unitService = unitService;
    }

    public BigDecimal normalize(String applicationAlias, String categoryAlias, BigDecimal value, String unitCode) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = PlatformNameRules.requireIdentifier(categoryAlias, "measureUnitCategoryAlias");
        MeasureUnitCategory category = categoryService.requireEnabledVisibleCategory(validApplicationAlias, validCategoryAlias);
        requireValidBaseUnit(category);
        MeasureUnit unit = unitService.requireEnabledUnitInCategory(category, unitCode);
        return normalize(value, unit);
    }

    public MeasureUnitConversion convert(String applicationAlias,
                                         String categoryAlias,
                                         BigDecimal value,
                                         String fromUnitCode,
                                         String toUnitCode) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = PlatformNameRules.requireIdentifier(categoryAlias, "measureUnitCategoryAlias");
        MeasureUnitCategory category = categoryService.requireEnabledVisibleCategory(validApplicationAlias, validCategoryAlias);
        requireValidBaseUnit(category);
        MeasureUnit from = unitService.requireEnabledUnitInCategory(category, fromUnitCode);
        MeasureUnit to = unitService.requireEnabledUnitInCategory(category, toUnitCode);
        BigDecimal baseValue = normalize(value, from);
        BigDecimal convertedValue = denormalize(baseValue, to);
        return new MeasureUnitConversion(validApplicationAlias, validCategoryAlias, value,
                from.getCode(), to.getCode(), baseValue, convertedValue);
    }

    private BigDecimal normalize(BigDecimal value, MeasureUnit unit) {
        if (value == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-conversion.measure-conversion-value-must-not-be-null",
                    "measure conversion value must not be null"));
        }
        return value.multiply(unit.getFactorToBase()).add(unit.getOffsetToBase());
    }

    private void requireValidBaseUnit(MeasureUnitCategory category) {
        if (category.getBaseUnitCode() == null || category.getBaseUnitCode().isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-conversion.measure-unit-category-requires-baseunitcode",
                    "measure unit category requires baseUnitCode: {value1}"), Map.of("value1", String.valueOf(category.getAlias())));
        }
        MeasureUnit baseUnit = unitService.requireEnabledUnitInCategory(category, category.getBaseUnitCode());
        if (baseUnit.getFactorToBase().compareTo(BigDecimal.ONE) != 0
                || baseUnit.getOffsetToBase().compareTo(BigDecimal.ZERO) != 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-conversion.measure-base-unit-must-use-factortobase-1-and-offsettobase-0",
                    "measure base unit must use factorToBase=1 and offsetToBase=0: {value1}"), Map.of("value1", String.valueOf(category.getBaseUnitCode())));
        }
    }

    private BigDecimal denormalize(BigDecimal baseValue, MeasureUnit unit) {
        BigDecimal shifted = baseValue.subtract(unit.getOffsetToBase());
        if (unit.getScale() != null) {
            return shifted.divide(unit.getFactorToBase(), unit.getScale(), unit.getRoundingMode());
        }
        return shifted.divide(unit.getFactorToBase(), MathContext.DECIMAL128);
    }
}
