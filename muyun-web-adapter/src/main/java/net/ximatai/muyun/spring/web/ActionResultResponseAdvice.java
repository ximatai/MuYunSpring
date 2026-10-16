package net.ximatai.muyun.spring.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.action.CommittedChangeSet;
import net.ximatai.muyun.spring.ability.action.DataChangeModuleAliasResolver;
import net.ximatai.muyun.spring.ability.action.MutationContext;
import net.ximatai.muyun.spring.ability.action.MutationContextHolder;
import net.ximatai.muyun.spring.web.realtime.DataChangeRealtimePublisher;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import net.ximatai.muyun.spring.ability.action.ActionMessage;
import net.ximatai.muyun.spring.common.i18n.TextResolver;

@RestControllerAdvice
@ConditionalOnBean(DataChangeModuleAliasResolver.class)
public class ActionResultResponseAdvice implements ResponseBodyAdvice<Object> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActionResultResponseAdvice.class);

    private final DataChangeModuleAliasResolver moduleAliasResolver;
    private final ObjectMapper objectMapper;
    private final DataChangeRealtimePublisher dataChangeRealtimePublisher;

    public ActionResultResponseAdvice(DataChangeModuleAliasResolver moduleAliasResolver,
                                      ObjectMapper objectMapper) {
        this(moduleAliasResolver, objectMapper, (DataChangeRealtimePublisher) null);
    }

    @Autowired
    public ActionResultResponseAdvice(DataChangeModuleAliasResolver moduleAliasResolver,
                                      ObjectMapper objectMapper,
                                      ObjectProvider<DataChangeRealtimePublisher> dataChangeRealtimePublisher) {
        this(moduleAliasResolver, objectMapper,
                dataChangeRealtimePublisher == null ? null : dataChangeRealtimePublisher.getIfAvailable());
    }

    ActionResultResponseAdvice(DataChangeModuleAliasResolver moduleAliasResolver,
                               ObjectMapper objectMapper,
                               DataChangeRealtimePublisher dataChangeRealtimePublisher) {
        this.moduleAliasResolver = moduleAliasResolver;
        this.objectMapper = objectMapper;
        this.dataChangeRealtimePublisher = dataChangeRealtimePublisher;
    }

    private TextResolver textResolver;

    @Autowired(required = false)
    void setTextResolver(TextResolver resolver) { this.textResolver = resolver; }

    private ActionMessage presentationMessage(ActionMessage message) {
        if (message == null || message.textSource() == null || textResolver == null) return message;
        return new ActionMessage(message.code(), textResolver.resolve(message.textSource(), message.messageArgs()).text(),
                message.type(), message.messageArgs(), message.textSource());
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        if (returnType == null || returnType.getMethod() == null) {
            return false;
        }
        if (MutationContextHolder.current().isEmpty()) {
            return false;
        }
        return WebAnnotationSupport.hasMergedMethodOrTypeAnnotation(returnType.getMethod(),
                returnType.getContainingClass(), BusinessMutation.class);
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ActionResultResponse || body instanceof PlatformWebError) {
            return body;
        }
        MutationContext context = MutationContextHolder.current().orElse(null);
        if (context == null) {
            return body;
        }
        reportAnnotatedMutationResult(returnType, request);
        CommittedChangeSet changeSet = context.committedChangeSet(moduleAliasResolver);
        CurrentUser sourceUser = CurrentUserContext.currentUser().orElse(null);
        context.afterCommit(moduleAliasResolver, committedChangeSet -> publishDataChange(committedChangeSet,
                sourceUser));
        ActionResultResponse actionResult = new ActionResultResponse(
                body,
                presentationMessage(context.message()),
                changeSet.changeSetId(),
                changeSet.changes()
        );
        if (selectedConverterType != null
                && StringHttpMessageConverter.class.isAssignableFrom(selectedConverterType)) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return json(actionResult);
        }
        return actionResult;
    }

    private void publishDataChange(CommittedChangeSet changeSet, CurrentUser sourceUser) {
        if (dataChangeRealtimePublisher != null) {
            try {
                if (sourceUser == null) {
                    dataChangeRealtimePublisher.publish(changeSet);
                    return;
                }
                try (CurrentUserContext.Scope ignored = CurrentUserContext.use(sourceUser)) {
                    dataChangeRealtimePublisher.publish(changeSet);
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("failed to publish data change set {}", changeSet.changeSetId(), ex);
            }
        }
    }

    private String json(ActionResultResponse actionResult) {
        try {
            return objectMapper.writeValueAsString(actionResult);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize action result", ex);
        }
    }

    private void reportAnnotatedMutationResult(MethodParameter returnType, ServerHttpRequest request) {
        BusinessMutationResult result = WebAnnotationSupport.findMergedMethodAnnotation(
                returnType.getMethod(), returnType.getContainingClass(), BusinessMutationResult.class);
        if (result == null) {
            return;
        }
        String moduleAlias = moduleAliasResolver.moduleAlias(result.module());
        switch (result.change()) {
            case UPDATED -> {
                requireRecordIdSource(result, BusinessMutationRecordIdSource.PATH_VARIABLE);
                BusinessMutationResultSupport.successUpdated(result.code(), result.message(),
                        moduleAlias, pathVariable(request, result.recordId()));
            }
            case COLLECTION_CHANGED -> {
                requireNoRecordId(result);
                BusinessMutationResultSupport.successCollectionChanged(
                        result.code(), result.message(), moduleAlias);
            }
        }
    }

    private void requireRecordIdSource(BusinessMutationResult result, BusinessMutationRecordIdSource source) {
        if (result.recordIdSource() != source) {
            throw new IllegalArgumentException(result.change() + " requires recordIdSource " + source);
        }
        if (result.recordId() == null || result.recordId().isBlank()) {
            throw new IllegalArgumentException(result.change() + " requires recordId");
        }
    }

    private void requireNoRecordId(BusinessMutationResult result) {
        if (result.recordIdSource() != BusinessMutationRecordIdSource.NONE) {
            throw new IllegalArgumentException(result.change() + " does not support recordIdSource");
        }
        if (result.recordId() != null && !result.recordId().isBlank()) {
            throw new IllegalArgumentException(result.change() + " does not support recordId");
        }
    }

    private String pathVariable(ServerHttpRequest request, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank");
        }
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            throw new IllegalArgumentException("path variables require servlet request");
        }
        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        Object variables = httpRequest.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("path variables are not available");
        }
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("path variable is required: " + key);
        }
        return value.toString();
    }
}
