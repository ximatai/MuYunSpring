package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import org.springframework.lang.NonNull;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.util.Optional;

public class ActionEndpointInterceptor implements AsyncHandlerInterceptor {
    private static final String ACTION_CONTEXT_SCOPE_ATTRIBUTE =
            ActionEndpointInterceptor.class.getName() + ".ACTION_CONTEXT_SCOPE";
    private static final String ACTING_CONTEXT_SCOPE_ATTRIBUTE =
            ActionEndpointInterceptor.class.getName() + ".ACTING_CONTEXT_SCOPE";
    private static final String ENDPOINT_ID_ATTRIBUTE =
            ActionEndpointInterceptor.class.getName() + ".ENDPOINT_ID";

    private final ActionExecutionPolicyService policyService;
    private final ActionEndpointContextResolver contextResolver;
    private final ActingRequestResolver actingRequestResolver;
    private final RegisteredWebEndpointCatalog endpointCatalog;

    public ActionEndpointInterceptor(ActionExecutionPolicyService policyService,
                                     ActionEndpointContextResolver contextResolver) {
        this(policyService, contextResolver, null, null);
    }

    public ActionEndpointInterceptor(ActionExecutionPolicyService policyService,
                                     ActionEndpointContextResolver contextResolver,
                                     ActingRequestResolver actingRequestResolver) {
        this(policyService, contextResolver, actingRequestResolver, null);
    }

    public ActionEndpointInterceptor(ActionExecutionPolicyService policyService,
                                     ActionEndpointContextResolver contextResolver,
                                     ActingRequestResolver actingRequestResolver,
                                     RegisteredWebEndpointCatalog endpointCatalog) {
        this.policyService = policyService;
        this.contextResolver = contextResolver;
        this.actingRequestResolver = actingRequestResolver;
        this.endpointCatalog = endpointCatalog;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        Optional<RegisteredWebEndpoint> registered = endpointCatalog == null
                ? Optional.empty()
                : endpointCatalog.find(request, handlerMethod);
        ActionEndpoint endpoint = WebAnnotationSupport.findMergedMethodAnnotation(handlerMethod.getMethod(),
                handlerMethod.getBeanType(), ActionEndpoint.class);
        CustomActionEndpoint customEndpoint = WebAnnotationSupport.findMergedMethodAnnotation(
                handlerMethod.getMethod(), handlerMethod.getBeanType(), CustomActionEndpoint.class);
        if (endpoint != null && customEndpoint != null) {
            throw new IllegalStateException("method cannot declare both standard and custom action endpoint: "
                    + handlerMethod.getBeanType().getName() + "#" + handlerMethod.getMethod().getName());
        }
        if (registered.isPresent() && (endpoint != null || customEndpoint != null)) {
            // The compiled catalog is authoritative for generated and compatibility mappings alike.
            endpoint = null;
            customEndpoint = null;
        }
        if (registered.isEmpty() && endpoint == null && customEndpoint == null) {
            return true;
        }
        registered.ifPresent(value -> {
            request.setAttribute(ENDPOINT_ID_ATTRIBUTE, value.definition().endpointId());
            MDC.put("endpointId", value.definition().endpointId());
        });
        Optional<ActionExecutionContext> context;
        if (registered.isPresent()) {
            context = Optional.of(contextResolver.resolve(request, registered.get().definition()));
        } else if (endpoint != null) {
            context = contextResolver.resolve(request, handlerMethod, endpoint);
        } else {
            context = contextResolver.resolve(request, handlerMethod, customEndpoint);
        }
        if (context.isEmpty()) {
            throw new IllegalStateException("action endpoint requires module alias: "
                    + handlerMethod.getBeanType().getName() + "#" + handlerMethod.getMethod().getName());
        }
        ActionExecutionContext resolved = context.get();
        MenuEntryRequestContext.requireModuleAlias(resolved.moduleAlias());
        ActingContextHolder.Scope actingScope = null;
        try {
            if (actingRequestResolver != null) {
                Optional<ActingContext> actingContext = actingRequestResolver.resolve(request, resolved);
                if (actingContext.isPresent()) {
                    actingScope = ActingContextHolder.use(actingContext.get());
                    request.setAttribute(ACTING_CONTEXT_SCOPE_ATTRIBUTE, actingScope);
                }
            }
            ActionAuthorizationResult authorization = policyService.authorize(resolved);
            request.setAttribute(ACTION_CONTEXT_SCOPE_ATTRIBUTE,
                    ActionExecutionContextHolder.use(resolved.withAuthorizationResult(authorization)));
            return true;
        } catch (RuntimeException ex) {
            clearEndpointId(request);
            if (actingScope != null) {
                request.removeAttribute(ACTING_CONTEXT_SCOPE_ATTRIBUTE);
                actingScope.close();
            }
            throw ex;
        }
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        closeActionContext(request);
        closeActingContext(request);
        clearEndpointId(request);
    }

    @Override
    public void afterConcurrentHandlingStarted(@NonNull HttpServletRequest request,
                                               @NonNull HttpServletResponse response,
                                               @NonNull Object handler) {
        closeActionContext(request);
        closeActingContext(request);
        clearEndpointId(request);
    }

    private void closeActionContext(HttpServletRequest request) {
        Object scope = request.getAttribute(ACTION_CONTEXT_SCOPE_ATTRIBUTE);
        request.removeAttribute(ACTION_CONTEXT_SCOPE_ATTRIBUTE);
        if (scope instanceof ActionExecutionContextHolder.Scope contextScope) {
            contextScope.close();
        }
    }

    private void closeActingContext(HttpServletRequest request) {
        Object scope = request.getAttribute(ACTING_CONTEXT_SCOPE_ATTRIBUTE);
        request.removeAttribute(ACTING_CONTEXT_SCOPE_ATTRIBUTE);
        if (scope instanceof ActingContextHolder.Scope actingScope) {
            actingScope.close();
        }
    }

    private void clearEndpointId(HttpServletRequest request) {
        request.removeAttribute(ENDPOINT_ID_ATTRIBUTE);
        MDC.remove("endpointId");
    }
}
