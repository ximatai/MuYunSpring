package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ximatai.muyun.spring.common.web.PlatformWebWireContract;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Map;

/**
 * Applies the standard-module numeric wire contract to an already application-serialized record.
 *
 * <p>The supplied mapper is the application's managed mapper. Serializing to a tree first keeps
 * mixins and registered serializers (such as {@code CodeTitleEnum}) authoritative before this
 * adapter changes only LONG and DECIMAL fields.</p>
 */
final class StaticModuleWebWireValues {
    static final String FIELD_TYPES_ATTRIBUTE = StaticModuleWebWireValues.class.getName() + ".fieldTypes";

    private StaticModuleWebWireValues() {
    }

    static void markCurrentResponse(Map<String, FieldValueType> fieldTypes) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null && fieldTypes != null && !fieldTypes.isEmpty()) {
            attributes.setAttribute(FIELD_TYPES_ATTRIBUTE, Map.copyOf(fieldTypes), RequestAttributes.SCOPE_REQUEST);
        }
    }

    static Object adapt(Object record, Map<String, FieldValueType> fieldTypes, ObjectMapper objectMapper) {
        if (record == null || fieldTypes == null || fieldTypes.isEmpty()) {
            return record;
        }
        JsonNode serialized = objectMapper.valueToTree(record);
        if (!(serialized instanceof ObjectNode values)) {
            return record;
        }
        fieldTypes.forEach((fieldName, fieldType) -> adaptPath(values, fieldName.split("\\."), 0, fieldType));
        return objectMapper.convertValue(values, Map.class);
    }

    static WebPageResponse<?> adaptPage(WebPageResponse<?> response,
                                   Map<String, FieldValueType> fieldTypes,
                                   ObjectMapper objectMapper) {
        if (response == null || fieldTypes == null || fieldTypes.isEmpty()) {
            return response;
        }
        List<Object> records = response.records().stream()
                .map(record -> adapt(record, fieldTypes, objectMapper))
                .toList();
        return new WebPageResponse<>(records, response.total(), response.pageNum(), response.pageSize(),
                response.pages(), response.totalKnown(), response.navigation());
    }

    private static boolean isLosslessNumeric(FieldValueType fieldType) {
        return fieldType == FieldValueType.LONG || fieldType == FieldValueType.DECIMAL;
    }

    private static void adaptPath(JsonNode node, String[] path, int index, FieldValueType fieldType) {
        if (node == null || index >= path.length || fieldType == null || !isLosslessNumeric(fieldType)) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> adaptPath(item, path, index, fieldType));
            return;
        }
        if (!(node instanceof ObjectNode object)) return;
        String segment = path[index];
        JsonNode value = object.get(segment);
        if (index + 1 < path.length) {
            adaptPath(value, path, index + 1, fieldType);
        } else if (value != null && !value.isNull()) {
            Object wireValue = PlatformWebWireContract.responseValue(fieldType.name(), value.asText());
            if (wireValue instanceof String text) object.put(segment, text);
        }
    }
}
