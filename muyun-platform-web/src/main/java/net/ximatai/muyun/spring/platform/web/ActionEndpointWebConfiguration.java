package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationCommandHandler;
import net.ximatai.muyun.spring.platform.web.notification.BusinessNotificationCommandDispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.ability.action.ActionMessageReporter;
import net.ximatai.muyun.spring.ability.action.DataChangeModuleAliasResolver;
import net.ximatai.muyun.spring.ability.action.DataChangeRecorder;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionCatalog;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.web.endpoint.DevelopmentEndpointCatalogReporter;
import net.ximatai.muyun.spring.platform.web.endpoint.StaticAbilityWebEndpointRegistrar;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeModeProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

@Configuration
public class ActionEndpointWebConfiguration {
    @Bean
    public BusinessNotificationCommandDispatcher businessNotificationCommandDispatcher(
            List<BusinessNotificationCommandHandler> handlers) {
        return new BusinessNotificationCommandDispatcher(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    public RegisteredWebEndpointCatalog registeredWebEndpointCatalog() {
        return new RegisteredWebEndpointCatalog();
    }

    @Bean
    @ConditionalOnMissingBean
    public StaticAbilityWebEndpointRegistrar staticAbilityWebEndpointRegistrar(
            ApplicationContext applicationContext,
            RequestMappingHandlerMapping handlerMapping,
            RegisteredWebEndpointCatalog endpointCatalog,
            ObjectProvider<RecycleBinFacade> recycleBinFacade,
            ObjectProvider<ObjectMapper> objectMapper,
            ObjectProvider<StaticModuleOpenApiEndpoint> staticModuleOpenApiEndpoint) {
        return new StaticAbilityWebEndpointRegistrar(applicationContext, handlerMapping, endpointCatalog,
                recycleBinFacade, objectMapper.getIfAvailable(ObjectMapper::new), staticModuleOpenApiEndpoint);
    }

    @Bean
    @ConditionalOnMissingBean
    public DevelopmentEndpointCatalogReporter developmentEndpointCatalogReporter(
            RegisteredWebEndpointCatalog endpointCatalog,
            ObjectProvider<PlatformRuntimeModeProvider> runtimeModeProvider) {
        return new DevelopmentEndpointCatalogReporter(endpointCatalog, runtimeModeProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionExecutionPolicyService actionExecutionPolicyService() {
        return new AllowAllActionExecutionPolicyService();
    }

    @Bean
    public ActionEndpointContextResolver actionEndpointContextResolver(
            ObjectProvider<PlatformModuleActionService> moduleActionService) {
        return new ActionEndpointContextResolver(moduleActionService.getIfAvailable());
    }

    @Bean
    public ActionEndpointInterceptor actionEndpointInterceptor(ActionExecutionPolicyService policyService,
                                                              ActionEndpointContextResolver contextResolver,
                                                              ObjectProvider<ActingRequestResolver> actingRequestResolver,
                                                              ObjectProvider<RegisteredWebEndpointCatalog>
                                                                      endpointCatalog) {
        return new ActionEndpointInterceptor(policyService, contextResolver,
                actingRequestResolver.getIfAvailable(),
                endpointCatalog.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public BusinessMutationInterceptor businessMutationInterceptor(
            ObjectProvider<RegisteredWebEndpointCatalog> endpointCatalog) {
        return new BusinessMutationInterceptor(endpointCatalog.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public DataChangeRecorder dataChangeRecorder() {
        return new DataChangeRecorder();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionMessageReporter actionMessageReporter() {
        return new ActionMessageReporter();
    }

    @Bean
    @ConditionalOnMissingBean
    public DataChangeModuleAliasResolver dataChangeModuleAliasResolver(
            StaticModuleDefinitionCatalog staticModuleDefinitionCatalog) {
        return new StaticModuleDataChangeAliasResolver(staticModuleDefinitionCatalog);
    }

    @Bean
    @ConditionalOnBean(ActionEndpointInterceptor.class)
    public WebMvcConfigurer actionEndpointInterceptorRegistration(
            ActionEndpointInterceptor actionEndpointInterceptor,
            ObjectProvider<BusinessMutationInterceptor> businessMutationInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(actionEndpointInterceptor)
                        .addPathPatterns("/**")
                        .order(Ordered.HIGHEST_PRECEDENCE + 200);
                BusinessMutationInterceptor mutationInterceptor = businessMutationInterceptor.getIfAvailable();
                if (mutationInterceptor != null) {
                    registry.addInterceptor(mutationInterceptor)
                            .addPathPatterns("/**")
                            .order(Ordered.HIGHEST_PRECEDENCE + 210);
                }
            }
        };
    }
}
