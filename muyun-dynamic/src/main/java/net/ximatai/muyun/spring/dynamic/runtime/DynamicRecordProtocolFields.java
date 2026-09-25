package net.ximatai.muyun.spring.dynamic.runtime;

import java.util.LinkedHashSet;
import java.util.Set;

/** Stable JSON envelope names that cannot also represent dynamic business fields. */
public final class DynamicRecordProtocolFields {
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "id", "tenantId", "version", "uiConfigId", "values", "children", "attachments", "originContext");
    private static final Set<String> RESERVED_BUSINESS_FIELD_NAMES;

    static {
        LinkedHashSet<String> names = new LinkedHashSet<>(ENVELOPE_FIELDS);
        names.add("record");
        RESERVED_BUSINESS_FIELD_NAMES = Set.copyOf(names);
    }

    private DynamicRecordProtocolFields() {
    }

    public static boolean isEnvelopeField(String fieldName) {
        return ENVELOPE_FIELDS.contains(fieldName);
    }

    public static boolean isReservedBusinessFieldName(String fieldName) {
        return RESERVED_BUSINESS_FIELD_NAMES.contains(fieldName);
    }

    public static Set<String> reservedBusinessFieldNames() {
        return RESERVED_BUSINESS_FIELD_NAMES;
    }
}
