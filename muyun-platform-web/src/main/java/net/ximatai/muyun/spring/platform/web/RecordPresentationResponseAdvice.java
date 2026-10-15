package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import java.util.*;

/** Read-only labels from opted-in producers; original record fields remain the edit and exchange contract. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RecordPresentationResponseAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectProvider<RegisteredWebEndpointCatalog> endpoints;
    private final ObjectProvider<RecordTextPresentationService> texts;
    public RecordPresentationResponseAdvice(ObjectProvider<RegisteredWebEndpointCatalog> endpoints, ObjectProvider<RecordTextPresentationService> texts) {
        this.endpoints = endpoints; this.texts = texts;
    }
    @Override public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servlet) || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) return body;
        var catalog = endpoints.getIfAvailable();
        var handler = servlet.getServletRequest().getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (catalog == null || !(handler instanceof HandlerMethod method)) return body;
        var endpoint = catalog.find(servlet.getServletRequest(), method).orElse(null);
        if (endpoint == null || !Set.of(PlatformAction.QUERY, PlatformAction.VIEW, PlatformAction.TREE, PlatformAction.REFERENCE, PlatformAction.RECYCLE_BIN_QUERY)
                .contains(endpoint.definition().action())) return body;
        var presentation = texts.getIfAvailable();
        return presentation == null ? body : presentation.project(endpoint.definition().moduleAlias(), body);
    }

}
