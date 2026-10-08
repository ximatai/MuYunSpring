package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.ability.action.MutationContext;
import net.ximatai.muyun.spring.ability.action.MutationContextHolder;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import org.springframework.lang.NonNull;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

public class BusinessMutationInterceptor implements AsyncHandlerInterceptor {
    private static final String MUTATION_CONTEXT_SCOPE_ATTRIBUTE =
            BusinessMutationInterceptor.class.getName() + ".MUTATION_CONTEXT_SCOPE";
    private final RegisteredWebEndpointCatalog endpointCatalog;

    public BusinessMutationInterceptor() {
        this(null);
    }

    public BusinessMutationInterceptor(RegisteredWebEndpointCatalog endpointCatalog) {
        this.endpointCatalog = endpointCatalog;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod) || !isBusinessMutation(request, handlerMethod)) {
            return true;
        }
        request.setAttribute(MUTATION_CONTEXT_SCOPE_ATTRIBUTE,
                MutationContextHolder.use(new MutationContext()));
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        closeMutationContext(request);
    }

    @Override
    public void afterConcurrentHandlingStarted(@NonNull HttpServletRequest request,
                                               @NonNull HttpServletResponse response,
                                               @NonNull Object handler) {
        closeMutationContext(request);
    }

    private boolean isBusinessMutation(HttpServletRequest request, HandlerMethod handlerMethod) {
        if (endpointCatalog != null) {
            java.util.Optional<RegisteredWebEndpoint> registered = endpointCatalog.find(request, handlerMethod);
            if (registered.isPresent()) {
                return switch (registered.get().definition().action()) {
                    case ENABLE, DISABLE, SORT, RECYCLE_BIN_RESTORE, RECYCLE_BIN_PURGE -> true;
                    default -> false;
                };
            }
        }
        BusinessMutation mutation = WebAnnotationSupport.findMergedMethodAnnotation(handlerMethod.getMethod(),
                handlerMethod.getBeanType(), BusinessMutation.class);
        if (mutation == null) {
            mutation = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), BusinessMutation.class);
        }
        return mutation != null && (!mutation.actionContextRequired()
                || ActionExecutionContextHolder.current().isPresent());
    }

    private void closeMutationContext(HttpServletRequest request) {
        Object scope = request.getAttribute(MUTATION_CONTEXT_SCOPE_ATTRIBUTE);
        request.removeAttribute(MUTATION_CONTEXT_SCOPE_ATTRIBUTE);
        if (scope instanceof MutationContextHolder.Scope contextScope) {
            contextScope.close();
        }
    }
}
