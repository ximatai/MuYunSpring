package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.common.option.OptionFieldDefinition;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;

import java.util.List;
import java.util.Map;

/** Source-neutral whitelist for the deliberately finite grouped-summary dimension. */
public final class ListQuerySummaryGroupFieldCatalog {
    private ListQuerySummaryGroupFieldCatalog() {}

    public static List<Field> list(List<FieldDefinition> fields, Map<String, OptionFieldDefinition> options,
                                   Map<String, ReferencePlan> references) {
        return (fields == null ? List.<FieldDefinition>of() : fields).stream()
                .map(field -> field(field, options, references)).filter(java.util.Objects::nonNull).toList();
    }

    public static void requireEligible(String fieldName, List<FieldDefinition> fields,
                                       Map<String, OptionFieldDefinition> options,
                                       Map<String, ReferencePlan> references, String context) {
        if (fieldName == null || fieldName.isBlank() || fieldName.contains(".")) {
            throw new IllegalArgumentException("grouped list query summary requires a direct main entity field: " + fieldName);
        }
        FieldDefinition field = (fields == null ? List.<FieldDefinition>of() : fields).stream()
                .filter(candidate -> fieldName.trim().equals(candidate.fieldName())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("grouped list query summary field is not declared: "
                        + prefix(context) + fieldName));
        if (field(field, options, references) == null) {
            throw new IllegalArgumentException("grouped list query summary field is not eligible: "
                    + prefix(context) + fieldName);
        }
    }

    private static Field field(FieldDefinition field, Map<String, OptionFieldDefinition> options,
                               Map<String, ReferencePlan> references) {
        if (field == null || !field.isPhysical() || field.protection().enabled()) return null;
        OptionFieldDefinition option = (options == null ? Map.<String, OptionFieldDefinition>of() : options)
                .get(field.fieldName());
        if (option != null && option.selectionMode() == OptionSelectionMode.SINGLE) {
            return new Field(field.fieldName(), field.name(), Kind.OPTION);
        }
        ReferencePlan reference = (references == null ? Map.<String, ReferencePlan>of() : references)
                .get(field.fieldName());
        if (reference != null && reference.cardinality() == ReferenceCardinality.ONE) {
            return new Field(field.fieldName(), field.name(), Kind.REFERENCE);
        }
        return null;
    }

    private static String prefix(String context) {
        return context == null || context.isBlank() ? "" : context.trim() + ".";
    }

    public enum Kind { OPTION, REFERENCE }
    public record Field(String fieldName, String title, Kind kind) {}
}
