package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.*;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods = false)
public class RecordExchangeWebConfiguration implements WebMvcConfigurer {
    private final org.springframework.beans.factory.ObjectProvider<RecordExchangeWebSupport>
            exchange;

    public RecordExchangeWebConfiguration(
            org.springframework.beans.factory.ObjectProvider<RecordExchangeWebSupport> exchange) {
        this.exchange = java.util.Objects.requireNonNull(exchange);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler) {
                        var support = exchange.getIfAvailable();
                        if (support != null)
                            request.setAttribute(RecordExchangeWebSupport.ATTRIBUTE, support);
                        return true;
                    }
                });
    }
}
