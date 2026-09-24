package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRuntimePublication;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/** Pins installed projections before action resolution and request deserialization, through response writing. */
public final class DynamicRuntimeReadInterceptor implements AsyncHandlerInterceptor {
    private static final String SCOPE = DynamicRuntimeReadInterceptor.class.getName() + ".scope";
    private final DynamicRuntimePublication publication;

    public DynamicRuntimeReadInterceptor(DynamicRuntimePublication publication) {
        this.publication = publication;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method
                && AnnotatedElementUtils.hasAnnotation(method.getBeanType(), DynamicRuntimeRead.class)) {
            Class<?> responseType = method.getMethod().getReturnType();
            if (java.util.concurrent.Callable.class.isAssignableFrom(responseType)
                    || java.util.concurrent.CompletionStage.class.isAssignableFrom(responseType)
                    || org.springframework.web.context.request.async.DeferredResult.class.isAssignableFrom(responseType)
                    || org.springframework.web.context.request.async.WebAsyncTask.class.isAssignableFrom(responseType)
                    || org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class.isAssignableFrom(responseType)
                    || org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody.class.isAssignableFrom(responseType)) {
                throw new IllegalStateException("DynamicRuntimeRead requires a synchronous endpoint");
            }
            request.setAttribute(SCOPE, publication.execution());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        close(request);
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        close(request);
    }

    private void close(HttpServletRequest request) {
        Object scope = request.getAttribute(SCOPE);
        request.removeAttribute(SCOPE);
        if (scope instanceof DynamicRuntimePublication.Scope execution) execution.close();
    }
}
