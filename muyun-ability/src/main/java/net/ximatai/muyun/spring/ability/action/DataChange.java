package net.ximatai.muyun.spring.ability.action;

import java.util.LinkedHashMap;
import java.util.Map;

public record DataChange(
        String type,
        String moduleAlias,
        String recordId,
        String resourceKey,
        String scope,
        Map<String, Object> facts
) {
    public DataChange {
        type = requireText(type, "type");
        moduleAlias = requireText(moduleAlias, "moduleAlias");
        recordId = normalize(recordId);
        resourceKey = normalize(resourceKey);
        scope = normalize(scope);
        facts = normalizeFacts(facts);
    }

    public static DataChange recordCreated(String moduleAlias, String recordId) {
        return new DataChange(DataChangeTypes.RECORD_CREATED, moduleAlias, recordId, null, null, Map.of());
    }

    public static DataChange recordUpdated(String moduleAlias, String recordId) {
        return new DataChange(DataChangeTypes.RECORD_UPDATED, moduleAlias, recordId, null, null, Map.of());
    }

    public static DataChange recordDeleted(String moduleAlias, String recordId) {
        return new DataChange(DataChangeTypes.RECORD_DELETED, moduleAlias, recordId, null, null, Map.of());
    }

    public static DataChange collectionChanged(String moduleAlias) {
        return new DataChange(DataChangeTypes.COLLECTION_CHANGED, moduleAlias, null, null, null, Map.of());
    }

    public static DataChange resourceRecordCreated(String moduleAlias, String resourceKey,
                                                   String scope, String recordId) {
        return new DataChange(DataChangeTypes.RECORD_CREATED, moduleAlias, recordId, resourceKey, scope, Map.of());
    }

    public static DataChange resourceRecordUpdated(String moduleAlias, String resourceKey,
                                                   String scope, String recordId) {
        return new DataChange(DataChangeTypes.RECORD_UPDATED, moduleAlias, recordId, resourceKey, scope, Map.of());
    }

    public static DataChange resourceRecordDeleted(String moduleAlias, String resourceKey,
                                                   String scope, String recordId) {
        return new DataChange(DataChangeTypes.RECORD_DELETED, moduleAlias, recordId, resourceKey, scope, Map.of());
    }

    private static Map<String, Object> normalizeFacts(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        facts.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                normalized.put(key.trim(), value);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String requireText(String value, String label) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
