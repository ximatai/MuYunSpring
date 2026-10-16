package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.content.BusinessContentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import jakarta.servlet.http.*;

/** Installs the narrow shared record bridge without business-controller dependency injection. */
@Configuration(proxyBeanMethods = false)
public class BusinessContentWebConfiguration implements WebMvcConfigurer {
    private final ObjectProvider<BusinessContentService> content;
    private final ObjectProvider<ModuleExecutionPlanCatalog> plans;
    public BusinessContentWebConfiguration(ObjectProvider<BusinessContentService> content,
                                            ObjectProvider<ModuleExecutionPlanCatalog> plans) {
        this.content = content; this.plans = plans;
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                var service = content.getIfAvailable(); var catalog = plans.getIfAvailable();
                if (service != null && catalog != null) request.setAttribute(BusinessContentWebSupport.ATTRIBUTE,
                        new BusinessContentWebSupport(service, catalog));
                return true;
            }
        });
    }
}
