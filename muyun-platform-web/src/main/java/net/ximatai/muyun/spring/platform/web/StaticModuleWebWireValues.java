package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ximatai.muyun.spring.common.web.PlatformWebWireContract;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
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
        fieldTypes.forEach((fieldName, fieldType) -> {
            JsonNode value = values.get(fieldName);
            if (value != null && !value.isNull() && fieldType != null && isLosslessNumeric(fieldType)) {
                Object wireValue = PlatformWebWireContract.responseValue(fieldType.name(), sourceValue(record, fieldName));
                if (wireValue instanceof String text) {
                    values.put(fieldName, text);
                }
            }
        });
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

    private static Object sourceValue(Object record, String fieldName) {
        if (record instanceof Map<?, ?> values) {
            return values.get(fieldName);
        }
        BeanWrapper bean = new BeanWrapperImpl(record);
        return bean.isReadableProperty(fieldName) ? bean.getPropertyValue(fieldName) : null;
    }
}
