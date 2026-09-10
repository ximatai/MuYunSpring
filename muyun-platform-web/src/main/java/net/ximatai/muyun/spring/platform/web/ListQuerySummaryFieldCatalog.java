package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;

import java.util.List;

/**
 * Source-neutral eligibility rules for built-in list SUM summaries.
 *
 * <p>A summary reads a database value without putting it through normal field output rendering,
 * so protected, virtual, and unit/currency-ambiguous source values must never enter this route.
 * Base values remain ordinary physical numeric fields and are intentionally eligible.</p>
 */
public final class ListQuerySummaryFieldCatalog {
    private ListQuerySummaryFieldCatalog() {}

    public static List<Field> list(List<FieldDefinition> fields) {
        return (fields == null ? List.<FieldDefinition>of() : fields).stream()
                .filter(ListQuerySummaryFieldCatalog::eligible)
                .map(field -> new Field(field.fieldName(), field.name()))
                .toList();
    }

    public static void requireEligible(String fieldName, List<FieldDefinition> fields, String context) {
        if (fieldName == null || fieldName.isBlank() || fieldName.contains(".")) {
            throw new IllegalArgumentException("sum list query summary requires a direct main entity field: " + fieldName);
        }
        FieldDefinition field = (fields == null ? List.<FieldDefinition>of() : fields).stream()
                .filter(candidate -> fieldName.trim().equals(candidate.fieldName())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("sum list query summary field is not declared: "
                        + prefix(context) + fieldName));
        if (!eligible(field)) {
            throw new IllegalArgumentException("sum list query summary field is not eligible: "
                    + prefix(context) + fieldName);
        }
    }

    public static boolean eligible(FieldDefinition field) {
        if (field == null || !field.isPhysical() || field.protection().enabled() || !numeric(field.type())) return false;
        if (field.money().enabled() && field.money().currencyMode() != FieldMoneyMode.FIXED) return false;
        return !field.measureUnit().enabled() || field.measureUnit().mode() == FieldMeasureUnitMode.FIXED;
    }

    private static boolean numeric(FieldType type) {
        return type == FieldType.INTEGER || type == FieldType.LONG || type == FieldType.DECIMAL;
    }

    private static String prefix(String context) {
        return context == null || context.isBlank() ? "" : context.trim() + ".";
    }

    public record Field(String fieldName, String title) {}
}
