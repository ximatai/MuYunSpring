package net.ximatai.muyun.spring.platform.web.endpoint;

import net.ximatai.muyun.spring.web.endpoint.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticActionContribution;
import net.ximatai.muyun.spring.platform.web.PlatformStaticActionContributionSupport;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebProjection;
import net.ximatai.muyun.spring.platform.module.StaticServiceAbilityCompiler;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import net.ximatai.muyun.spring.web.RecordWebProjectionPolicy;
import net.ximatai.muyun.spring.web.ScopedWeb;
import net.ximatai.muyun.spring.platform.web.StaticAbilityOperationRuntime;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApiEndpoint;
import net.ximatai.muyun.spring.platform.web.StandardWebEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade;
import net.ximatai.muyun.spring.platform.deletion.DeletionLogService;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Compiles service abilities into real Spring MVC mappings at application startup. */
public class StaticAbilityWebEndpointRegistrar implements SmartInitializingSingleton {
    private final ApplicationContext applicationContext;
    private final RequestMappingHandlerMapping handlerMapping;
    private final RegisteredWebEndpointCatalog endpointCatalog;
    private final ObjectProvider<RecycleBinFacade> recycleBinFacade;
    private final ObjectProvider<StaticModuleOpenApiEndpoint> staticModuleOpenApiEndpoint;
    private final PlatformWebOperationDispatcher dispatcher;
    private final StaticWebEndpointProjectionCompiler projectionCompiler = new StaticWebEndpointProjectionCompiler();

    public StaticAbilityWebEndpointRegistrar(ApplicationContext applicationContext,
                                             RequestMappingHandlerMapping handlerMapping,
                                             RegisteredWebEndpointCatalog endpointCatalog,
                                             ObjectProvider<RecycleBinFacade> recycleBinFacade) {
        this(applicationContext, handlerMapping, endpointCatalog, recycleBinFacade, new ObjectMapper());
    }

    public StaticAbilityWebEndpointRegistrar(ApplicationContext applicationContext,
                                             RequestMappingHandlerMapping handlerMapping,
                                             RegisteredWebEndpointCatalog endpointCatalog,
                                             ObjectProvider<RecycleBinFacade> recycleBinFacade,
                                             ObjectMapper objectMapper) {
        this(applicationContext, handlerMapping, endpointCatalog, recycleBinFacade, objectMapper, null);
    }

    public StaticAbilityWebEndpointRegistrar(ApplicationContext applicationContext,
                                             RequestMappingHandlerMapping handlerMapping,
                                             RegisteredWebEndpointCatalog endpointCatalog,
                                             ObjectProvider<RecycleBinFacade> recycleBinFacade,
                                             ObjectMapper objectMapper,
                                             ObjectProvider<StaticModuleOpenApiEndpoint> staticModuleOpenApiEndpoint) {
        this.applicationContext = applicationContext;
        this.handlerMapping = handlerMapping;
        this.endpointCatalog = endpointCatalog;
        this.recycleBinFacade = recycleBinFacade;
        this.staticModuleOpenApiEndpoint = staticModuleOpenApiEndpoint;
        this.dispatcher = new PlatformWebOperationDispatcher(endpointCatalog, objectMapper,
                new StaticAbilityOperationRuntime(recycleBinFacade,
                        applicationContext.getBeanProvider(DeletionLogService.class)));
    }

    @Override
    public void afterSingletonsInstantiated() {
        suppressDisabledDefaultControllerMappings();
        Set<String> compiledProjectionBeans = new LinkedHashSet<>();
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticModule.class)) {
            Object bean = applicationContext.getBean(beanName);
            if (!(bean instanceof ScopedWeb<?> anchor)) {
                continue;
            }
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            PlatformStaticModule module = AnnotationUtils.findAnnotation(beanClass, PlatformStaticModule.class);
            if (module == null) {
                continue;
            }
            List<String> basePaths = basePaths(beanClass, module.alias());
            Object service = anchor.service();
            contribute(module.alias(), basePaths, anchor, service, null, Set.of(), "");
            compiledProjectionBeans.add(beanName);
        }
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticActionContribution.class)) {
            Object bean = applicationContext.getBean(beanName);
            if (!(bean instanceof ScopedWeb<?> anchor)) {
                continue;
            }
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            PlatformStaticActionContribution contribution =
                    AnnotationUtils.findAnnotation(beanClass, PlatformStaticActionContribution.class);
            if (contribution == null) {
                continue;
            }
            String moduleAlias = PlatformStaticActionContributionSupport.targetModule(contribution);
            contribute(moduleAlias, basePaths(beanClass, moduleAlias), anchor, anchor.service(), contribution,
                    Set.of(), "");
            compiledProjectionBeans.add(beanName);
        }
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticWebProjection.class)) {
            Object bean = applicationContext.getBean(beanName);
            if (!(bean instanceof ScopedWeb<?> anchor)) {
                throw new IllegalStateException("@PlatformStaticWebProjection requires ScopedWeb: "
                        + AopUtils.getTargetClass(bean).getName());
            }
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            PlatformStaticWebProjection projection =
                    AnnotationUtils.findAnnotation(beanClass, PlatformStaticWebProjection.class);
            if (projection == null) {
                continue;
            }
            String moduleAlias = PlatformNameRules.requireModuleAlias(projection.module());
            contribute(moduleAlias, basePaths(beanClass, moduleAlias), anchor, anchor.service(), null,
                    disabledOperations(projection), ".projection");
            compiledProjectionBeans.add(beanName);
        }
        requireAnchoredStandardProjections(compiledProjectionBeans);
        registerStaticModuleOpenApiEndpoints();
        registerExplicitControllerEndpoints();
    }

    private void suppressDisabledDefaultControllerMappings() {
        List<RequestMappingInfo> suppressed = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> isDisabledDefaultControllerMapping(entry.getValue()))
                .map(java.util.Map.Entry::getKey)
                .toList();
        suppressed.forEach(handlerMapping::unregisterMapping);
    }

    private boolean isDisabledDefaultControllerMapping(HandlerMethod handler) {
        ActionEndpoint endpoint = AnnotationUtils.findAnnotation(handler.getMethod(), ActionEndpoint.class);
        if (endpoint == null || !StandardWebEndpoint.isDefault(handler.getMethod())) {
            return false;
        }
        Object controller = handler.getBean();
        if (controller instanceof String beanName) {
            controller = applicationContext.getBean(beanName);
        }
        Class<?> controllerClass = AopUtils.getTargetClass(controller);
        if (!isStaticWebAnchor(controllerClass)) {
            return false;
        }
        return controller instanceof ScopedWeb<?> scopedWeb
                && StaticServiceAbilityCompiler.disabledActions(scopedWeb.service()).contains(endpoint.value());
    }

    private boolean isStaticWebAnchor(Class<?> controllerClass) {
        return AnnotationUtils.findAnnotation(controllerClass, PlatformStaticModule.class) != null
                || AnnotationUtils.findAnnotation(controllerClass, PlatformStaticActionContribution.class) != null
                || AnnotationUtils.findAnnotation(controllerClass, PlatformStaticWebProjection.class) != null;
    }

    private void contribute(String moduleAlias,
                            List<String> basePaths,
                            ScopedWeb<?> anchor,
                            Object service,
                            PlatformStaticActionContribution contribution,
                            Set<PlatformAction> disabledOperations,
                            String endpointIdNamespace) {
        List<PlatformOperationDefinition> operations =
                StaticServiceAbilityCompiler.standardOperations(service, CapabilityModuleRegistry.defaultRegistry());
        if (operations.stream()
                .anyMatch(operation -> operation.action().name().startsWith("RECYCLE_BIN"))
                && recycleBinFacade.getIfAvailable() == null) {
            throw new IllegalStateException("RecycleBinFacade is required by " + moduleAlias + ".recycleBin");
        }
        StaticWebOperationTarget target = new StaticWebOperationTarget(moduleAlias, anchor, service);
        projectionCompiler.compile(moduleAlias, basePaths, operations.stream()
                        .filter(operation -> !disabledOperations.contains(operation.action()))
                        .toList(), contribution, endpointIdNamespace)
                .forEach(projection -> register(target, projection));
    }

    private Set<PlatformAction> disabledOperations(PlatformStaticWebProjection projection) {
        if (projection.disabledOperations().length == 0) {
            return Set.of();
        }
        EnumSet<PlatformAction> disabled = EnumSet.noneOf(PlatformAction.class);
        java.util.Collections.addAll(disabled, projection.disabledOperations());
        return Set.copyOf(disabled);
    }

    private void requireAnchoredStandardProjections(Set<String> compiledProjectionBeans) {
        for (String beanName : applicationContext.getBeanNamesForType(RecordWebProjectionPolicy.class)) {
            if (compiledProjectionBeans.contains(beanName)) {
                continue;
            }
            Object bean = applicationContext.getBean(beanName);
            throw new IllegalStateException("standard Web projection requires @PlatformStaticModule, "
                    + "@PlatformStaticActionContribution, or @PlatformStaticWebProjection: "
                    + AopUtils.getTargetClass(bean).getName());
        }
    }

    private void register(StaticWebOperationTarget target, WebEndpointProjection projection) {
        ResolvedWebEndpoint definition = projection.resolve();
        RequestMappingInfo mapping = RequestMappingInfo.paths(definition.path())
                    .methods(definition.method())
                    .options(handlerMapping.getBuilderConfiguration())
                    .build();
        ExistingMapping existing = findExistingMapping(definition.path(), definition.method());
        if (existing != null) {
            throw new IllegalStateException("explicit controller cannot replace enabled standard ability endpoint "
                    + definition.method() + " " + definition.path()
                    + "; disable " + definition.action().code() + " on the concrete service first: "
                    + existing.handler());
        }
        try {
            handlerMapping.registerMapping(mapping, dispatcher, dispatcher.handlerMethod());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("failed to register ability endpoint " + definition.endpointId()
                    + " at " + definition.method() + " " + definition.path(), failure);
        }
        endpointCatalog.register(new RegisteredWebEndpoint(definition, mapping, dispatcher, dispatcher.handlerMethod(), target));
    }

    private ExistingMapping findExistingMapping(String path, RequestMethod method) {
        List<ExistingMapping> matches = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().contains(path))
                .filter(entry -> entry.getKey().getMethodsCondition().getMethods().isEmpty()
                        || entry.getKey().getMethodsCondition().getMethods().contains(method))
                .map(entry -> new ExistingMapping(entry.getKey(), entry.getValue()))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("multiple explicit mappings conflict with ability endpoint "
                    + method + " " + path + ": " + matches.stream().map(ExistingMapping::handler).toList());
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private void registerExplicitControllerEndpoints() {
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            if (endpointCatalog.contains(mapping, handler)) {
                return;
            }
            ActionEndpoint endpoint = AnnotationUtils.findAnnotation(handler.getMethod(), ActionEndpoint.class);
            String moduleAlias = explicitModuleAlias(handler.getBeanType());
            if (endpoint == null || moduleAlias == null) {
                return;
            }
            List<RequestMethod> methods = List.copyOf(mapping.getMethodsCondition().getMethods());
            if (methods.size() != 1) {
                throw new IllegalStateException("platform action endpoint requires exactly one HTTP method: "
                        + handler);
            }
            for (String path : mapping.getPatternValues()) {
                String operationCode = handler.getMethod().getName();
                String endpointId = moduleAlias + ".controller." + operationCode + "."
                        + methods.getFirst().name().toLowerCase(Locale.ROOT) + "."
                        + Integer.toUnsignedString(path.hashCode(), 36);
                ResolvedWebEndpoint definition = new ResolvedWebEndpoint(
                        endpointId,
                        moduleAlias,
                        "controller",
                        operationCode,
                        endpoint.value(),
                        methods.getFirst(),
                        path,
                        ResolvedWebEndpoint.Source.STATIC_EXPLICIT
                );
                endpointCatalog.register(new RegisteredWebEndpoint(
                        definition, mapping, handler.getBean(), handler.getMethod()));
            }
        });
    }

    private void registerStaticModuleOpenApiEndpoints() {
        if (staticModuleOpenApiEndpoint == null) {
            return;
        }
        StaticModuleOpenApiEndpoint endpoint = staticModuleOpenApiEndpoint.getIfAvailable();
        if (endpoint == null) {
            return;
        }
        for (String beanName : applicationContext.getBeanNamesForAnnotation(StaticModuleOpenApi.class)) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            PlatformStaticModule module = AnnotationUtils.findAnnotation(beanClass, PlatformStaticModule.class);
            if (module == null) {
                throw new IllegalStateException("@StaticModuleOpenApi requires @PlatformStaticModule: "
                        + beanClass.getName());
            }
            for (String basePath : basePaths(beanClass, module.alias())) {
                registerStaticModuleOpenApiEndpoint(endpoint, module.alias(), basePath + "/openapi");
            }
        }
    }

    private void registerStaticModuleOpenApiEndpoint(StaticModuleOpenApiEndpoint endpoint,
                                                     String moduleAlias,
                                                     String path) {
        endpoint.register(moduleAlias, path);
        RequestMappingInfo mapping = RequestMappingInfo.paths(path)
                .methods(RequestMethod.GET)
                .options(handlerMapping.getBuilderConfiguration())
                .build();
        ExistingMapping existing = findExistingMapping(path, RequestMethod.GET);
        if (existing != null) {
            throw new IllegalStateException("static module OpenAPI mapping already exists at GET " + path
                    + ": " + existing.handler());
        }
        HandlerMethod handlerMethod = new HandlerMethod(endpoint,
                org.springframework.util.ReflectionUtils.findMethod(StaticModuleOpenApiEndpoint.class,
                        "openApi", jakarta.servlet.http.HttpServletRequest.class));
        try {
            handlerMapping.registerMapping(mapping, endpoint, handlerMethod.getMethod());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("failed to register static module OpenAPI endpoint at GET " + path,
                    failure);
        }
        String endpointId = moduleAlias + ".openApi.get."
                + Integer.toUnsignedString(path.hashCode(), 36);
        ResolvedWebEndpoint definition = new ResolvedWebEndpoint(endpointId, moduleAlias, "openApi", "openApi",
                PlatformAction.VIEW, RequestMethod.GET, path, ResolvedWebEndpoint.Source.STATIC_EXPLICIT);
        endpointCatalog.register(new RegisteredWebEndpoint(definition, mapping, endpoint, handlerMethod.getMethod()));
    }

    private String explicitModuleAlias(Class<?> beanClass) {
        PlatformStaticModule module = AnnotationUtils.findAnnotation(beanClass, PlatformStaticModule.class);
        return module == null ? null : module.alias();
    }

    private List<String> basePaths(Class<?> beanClass, String moduleAlias) {
        RequestMapping mapping = AnnotationUtils.findAnnotation(beanClass, RequestMapping.class);
        if (mapping == null) {
            return List.of("/" + moduleAlias);
        }
        String[] values = mapping.path().length == 0 ? mapping.value() : mapping.path();
        if (values.length == 0) {
            return List.of("/" + moduleAlias);
        }
        return Arrays.stream(values).map(this::normalizePath).toList();
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            return "";
        }
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private record ExistingMapping(RequestMappingInfo mapping,
                                   org.springframework.web.method.HandlerMethod handler) {
    }
}
