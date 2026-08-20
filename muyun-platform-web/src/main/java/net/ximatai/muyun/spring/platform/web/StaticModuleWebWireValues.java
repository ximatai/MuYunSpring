package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.annotation.JsonValue;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.web.PlatformWebWireContract;
import net.ximatai.muyun.spring.web.WebPageResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/** Applies the standard module wire contract after record-output transformations. */
final class StaticModuleWebWireValues {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private StaticModuleWebWireValues() {
    }

    static Object record(Object record, Map<String, FieldValueType> fieldTypes) {
        if (record == null || fieldTypes == null || fieldTypes.isEmpty()) {
            return record;
        }
        Map<String, Object> values = record instanceof Map<?, ?> map
                ? copyMap(map)
                : OBJECT_MAPPER.convertValue(record, new TypeReference<LinkedHashMap<String, Object>>() { });
        fieldTypes.forEach((fieldName, fieldType) -> {
            if (values.containsKey(fieldName) && fieldType != null) {
                values.put(fieldName, PlatformWebWireContract.responseValue(fieldType.name(), values.get(fieldName)));
            }
        });
        return new WireRecord(values);
    }

    static WebPageResponse<?> page(WebPageResponse<?> response, Map<String, FieldValueType> fieldTypes) {
        if (response == null || fieldTypes == null || fieldTypes.isEmpty()) {
            return response;
        }
        List<Object> records = response.records().stream().map(record -> record(record, fieldTypes)).toList();
        return new WebPageResponse<>(records, response.total(), response.pageNum(), response.pageSize(),
                response.pages(), response.totalKnown(), response.navigation());
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    /** Keeps CrudWeb's source-compatible EntityContract return type while serializing its map value. */
    private static final class WireRecord implements EntityContract {
        private final Map<String, Object> values;

        private WireRecord(Map<String, Object> values) {
            this.values = values;
        }

        @JsonValue
        public Map<String, Object> values() {
            return values;
        }

        @Override public String getId() { return string("id"); }
        @Override public void setId(String value) { values.put("id", value); }
        @Override public String getTenantId() { return string("tenantId"); }
        @Override public void setTenantId(String value) { values.put("tenantId", value); }
        @Override public Integer getVersion() { return integer("version"); }
        @Override public void setVersion(Integer value) { values.put("version", value); }
        @Override public Boolean getDeleted() { return (Boolean) values.get("deleted"); }
        @Override public void setDeleted(Boolean value) { values.put("deleted", value); }
        @Override public Instant getDeletedAt() { return instant("deletedAt"); }
        @Override public void setDeletedAt(Instant value) { values.put("deletedAt", value); }
        @Override public String getDeletedBy() { return string("deletedBy"); }
        @Override public void setDeletedBy(String value) { values.put("deletedBy", value); }
        @Override public String getCreatedBy() { return string("createdBy"); }
        @Override public void setCreatedBy(String value) { values.put("createdBy", value); }
        @Override public Instant getCreatedAt() { return instant("createdAt"); }
        @Override public void setCreatedAt(Instant value) { values.put("createdAt", value); }
        @Override public String getUpdatedBy() { return string("updatedBy"); }
        @Override public void setUpdatedBy(String value) { values.put("updatedBy", value); }
        @Override public Instant getUpdatedAt() { return instant("updatedAt"); }
        @Override public void setUpdatedAt(Instant value) { values.put("updatedAt", value); }

        private String string(String key) { return (String) values.get(key); }
        private Integer integer(String key) { return (Integer) values.get(key); }
        private Instant instant(String key) { return (Instant) values.get(key); }
    }
}
