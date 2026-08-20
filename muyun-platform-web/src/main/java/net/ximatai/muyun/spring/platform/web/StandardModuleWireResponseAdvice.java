package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;

/** Applies standard-module numeric adaptation after controller methods have returned their domain type. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StandardModuleWireResponseAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectMapper objectMapper;

    public StandardModuleWireResponseAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        Object value = RequestContextHolder.currentRequestAttributes().getAttribute(
                StaticModuleWebWireValues.FIELD_TYPES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (!(value instanceof Map<?, ?> fields) || body == null) {
            return body;
        }
        Map<String, FieldValueType> fieldTypes = (Map<String, FieldValueType>) fields;
        return body instanceof WebPageResponse<?> page
                ? StaticModuleWebWireValues.adaptPage(page, fieldTypes, objectMapper)
                : StaticModuleWebWireValues.adapt(body, fieldTypes, objectMapper);
    }
}
