package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.content.BusinessContentService;
import net.ximatai.muyun.spring.platform.content.BusinessContentTranslationService;
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
    private final ObjectProvider<BusinessContentTranslationService> translations;
    public BusinessContentWebConfiguration(ObjectProvider<BusinessContentService> content,
                                            ObjectProvider<ModuleExecutionPlanCatalog> plans, ObjectProvider<BusinessContentTranslationService> translations) {
        this.content = content; this.plans = plans; this.translations = translations;
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                var service = content.getIfAvailable(); var catalog = plans.getIfAvailable();
                if (service != null && catalog != null) request.setAttribute(BusinessContentWebSupport.ATTRIBUTE,
                        new BusinessContentWebSupport(service, catalog, translations.getIfAvailable()));
                return true;
            }
        });
    }
}
