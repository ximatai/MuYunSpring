package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.web.BusinessMutation;
import net.ximatai.muyun.spring.web.WebAnnotationSupport;
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
        if (!(handler instanceof HandlerMethod method)) return body;
        var endpoint = catalog == null ? null : catalog.find(servlet.getServletRequest(), method).orElse(null);
        // Dynamic routes are ordinary MVC mappings; their declared action is the same read contract.
        var declaration = WebAnnotationSupport.findMergedMethodAnnotation(method.getMethod(), method.getBeanType(), ActionEndpoint.class);
        var action = endpoint != null ? endpoint.definition().action() : declaration == null ? null : declaration.value();
        if (action == null || !Set.of(PlatformAction.QUERY, PlatformAction.VIEW, PlatformAction.TREE, PlatformAction.REFERENCE, PlatformAction.RECYCLE_BIN_QUERY, PlatformAction.UPDATE)
                .contains(action)) return body;
        // Aggregate retained-child reads require UPDATE permission, but are not mutation endpoints.
        if (action == PlatformAction.UPDATE &&
                WebAnnotationSupport.hasMergedMethodOrTypeAnnotation(method.getMethod(), method.getBeanType(), BusinessMutation.class)) return body;
        var presentation = texts.getIfAvailable();
        return presentation == null ? body : presentation.projectCaptured(body);
    }

}
