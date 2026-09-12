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
    private final StaticCrudActionLogRecorder staticCrudActionLogRecorder;

    public ActionEndpointInterceptor(ActionExecutionPolicyService policyService,
                                     ActionEndpointContextResolver contextResolver) {
        this(policyService, contextResolver, null, null, null);
    }

    public ActionEndpointInterceptor(ActionExecutionPolicyService policyService,
                                     ActionEndpointContextResolver contextResolver,
                                     ActingRequestResolver actingRequestResolver) {
        this(policyService, contextResolver, actingRequestResolver, null, null);
    }

    public ActionEndpointInterceptor(ActionExecutionPolicyService policyService,
                                     ActionEndpointContextResolver contextResolver,
                                     ActingRequestResolver actingRequestResolver,
                                     RegisteredWebEndpointCatalog endpointCatalog) {
        this(policyService, contextResolver, actingRequestResolver, endpointCatalog, null);
    }

    public ActionEndpointInterceptor(ActionExecutionPolicyService policyService,
                                     ActionEndpointContextResolver contextResolver,
                                     ActingRequestResolver actingRequestResolver,
                                     RegisteredWebEndpointCatalog endpointCatalog,
                                     StaticCrudActionLogRecorder staticCrudActionLogRecorder) {
        this.policyService = policyService;
        this.contextResolver = contextResolver;
        this.actingRequestResolver = actingRequestResolver;
        this.endpointCatalog = endpointCatalog;
        this.staticCrudActionLogRecorder = staticCrudActionLogRecorder == null ? new StaticCrudActionLogRecorder(null) : staticCrudActionLogRecorder;
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
        ActingContextHolder.Scope actingScope = null;
        try {
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
            registered.ifPresent(value -> {
                request.setAttribute(ENDPOINT_ID_ATTRIBUTE, value.definition().endpointId());
                MDC.put("endpointId", value.definition().endpointId());
            });
            MenuEntryRequestContext.requireModuleAlias(resolved.moduleAlias(), pageEntryParentModuleAlias(handlerMethod));
            putActionLogContext(resolved);
            if (actingRequestResolver != null) {
                Optional<ActingContext> actingContext = actingRequestResolver.resolve(request, resolved);
                if (actingContext.isPresent()) {
                    actingScope = ActingContextHolder.use(actingContext.get());
                    request.setAttribute(ACTING_CONTEXT_SCOPE_ATTRIBUTE, actingScope);
                }
            }
            ActionAuthorizationResult authorization = policyService.authorize(resolved);
            ActionExecutionContext authorized = resolved.withAuthorizationResult(authorization);
            request.setAttribute(ACTION_CONTEXT_SCOPE_ATTRIBUTE, ActionExecutionContextHolder.use(authorized));
            if (handlerMethod.getBean() instanceof CrudWeb<?, ?> && isStaticCrudMutation(authorized)) {
                staticCrudActionLogRecorder.begin(request);
            }
            return true;
        } catch (RuntimeException ex) {
            clearLogContext(request);
            if (actingScope != null) {
                request.removeAttribute(ACTING_CONTEXT_SCOPE_ATTRIBUTE);
                actingScope.close();
            }
            throw ex;
        }
    }

    private static String pageEntryParentModuleAlias(HandlerMethod handlerMethod) {
        PlatformPageEntryChild child = handlerMethod.getBeanType().getAnnotation(PlatformPageEntryChild.class);
        return child == null ? null : child.parentModuleAlias();
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        if (handler instanceof HandlerMethod handlerMethod
                && handlerMethod.getBean() instanceof CrudWeb<?, ?>
                && ActionExecutionContextHolder.current().filter(this::isStaticCrudMutation).isPresent()) {
            staticCrudActionLogRecorder.record(request, ActionExecutionContextHolder.current().orElseThrow(), ex);
        }
        closeActionContext(request);
        closeActingContext(request);
        clearLogContext(request);
    }

    @Override
    public void afterConcurrentHandlingStarted(@NonNull HttpServletRequest request,
                                               @NonNull HttpServletResponse response,
                                               @NonNull Object handler) {
        closeActionContext(request);
        closeActingContext(request);
        clearLogContext(request);
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

    private void putActionLogContext(ActionExecutionContext context) {
        MDC.put("moduleAlias", context.moduleAlias());
        MDC.put("actionCode", context.actionCode());
    }

    private boolean isStaticCrudMutation(ActionExecutionContext context) {
        return context.platformAction() == net.ximatai.muyun.spring.common.platform.PlatformAction.CREATE
                || context.platformAction() == net.ximatai.muyun.spring.common.platform.PlatformAction.UPDATE
                || context.platformAction() == net.ximatai.muyun.spring.common.platform.PlatformAction.DELETE;
    }

    private void clearLogContext(HttpServletRequest request) {
        request.removeAttribute(ENDPOINT_ID_ATTRIBUTE);
        MDC.remove("endpointId");
        MDC.remove("moduleAlias");
        MDC.remove("actionCode");
    }
}
