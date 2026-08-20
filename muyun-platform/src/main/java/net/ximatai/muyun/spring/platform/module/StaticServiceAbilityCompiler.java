package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.PlatformOperation;
import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityOperationContext;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityModule;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityRegistry;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferencerAbility;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles static service interface composition into the shared platform capability vocabulary. */
public final class StaticServiceAbilityCompiler {
    private StaticServiceAbilityCompiler() {
    }

    /**
     * Compiles baseline service abilities plus registered static facets.  The registry is a
     * source-neutral input, so this compiler never knows a dynamic capability implementation.
     */
    public static Set<EntityCapability> compile(Object service, StaticCapabilityRegistry registry) {
        EnumSet<EntityCapability> capabilities = EnumSet.noneOf(EntityCapability.class);
        if (service instanceof CrudAbility<?>) capabilities.add(EntityCapability.CRUD);
        if (service instanceof SoftDeleteAbility<?>) capabilities.add(EntityCapability.SOFT_DELETE);
        if (service instanceof CacheAbility<?>) capabilities.add(EntityCapability.CACHE);
        registry.staticModules().forEach(module -> module.staticFacet()
                .filter(facet -> facet.supports(service))
                .ifPresent(facet -> capabilities.add(module.capability())));
        if (service instanceof ReferenceAbility<?>) capabilities.add(EntityCapability.REFERENCE);
        if (service instanceof ReferencerAbility<?>) capabilities.add(EntityCapability.REFERENCE_DEPENDENCY);
        if (service instanceof DataScopeAbility<?>) capabilities.add(EntityCapability.DATA_SCOPE);
        if (service instanceof ChildrenAbility<?>) capabilities.add(EntityCapability.CHILD_RELATION);
        validateDependencies(capabilities, registry.staticModules(), service);
        return Set.copyOf(capabilities);
    }

    /** Registry overload is used by capability-contract tests and platform composition. */
    public static List<PlatformOperationDefinition> standardOperations(Object service, StaticCapabilityRegistry registry) {
        List<PlatformOperationDefinition> operations = new ArrayList<>();
        Set<EntityCapability> capabilities = compile(service, registry);
        StaticCapabilityOperationContext context = new StaticCapabilityOperationContext(service, capabilities,
                operationMethods(service));
        registry.staticModules().forEach(module -> module.staticFacet()
                .filter(facet -> capabilities.contains(module.capability()))
                .ifPresent(facet -> operations.addAll(facet.standardOperations(context))));
        Set<PlatformAction> disabled = disabledActions(service);
        return operations.stream().filter(operation -> !disabled.contains(operation.action())).toList();
    }

    public static List<PlatformAction> standardActions(Object service, StaticCapabilityRegistry registry) {
        return standardOperations(service, registry).stream()
                .map(PlatformOperationDefinition::action)
                .distinct()
                .toList();
    }

    public static Map<PlatformAction, Method> operationMethods(Object service) {
        if (service == null) {
            return Map.of();
        }
        Set<PlatformAction> disabled = disabledActions(service);
        LinkedHashMap<PlatformAction, Method> methods = new LinkedHashMap<>();
        for (Class<?> serviceInterface : allInterfaces(AopUtils.getTargetClass(service))) {
            for (Method method : serviceInterface.getDeclaredMethods()) {
                PlatformOperation operation = method.getAnnotation(PlatformOperation.class);
                if (operation == null || disabled.contains(operation.value())) {
                    continue;
                }
                Method existing = methods.putIfAbsent(operation.value(), method);
                if (existing != null && !existing.equals(method)) {
                    throw new IllegalStateException("duplicate platform operation " + operation.value().code()
                            + " on " + service.getClass().getName() + ": " + existing + " and " + method);
                }
            }
        }
        return Map.copyOf(methods);
    }

    public static Set<PlatformAction> disabledActions(Object service) {
        if (service == null) {
            return Set.of();
        }
        Class<?> serviceClass = AopUtils.getTargetClass(service);
        DisablePlatformOperations annotation = serviceClass.getDeclaredAnnotation(DisablePlatformOperations.class);
        if (annotation == null || annotation.value().length == 0) {
            return Set.of();
        }
        EnumSet<PlatformAction> actions = EnumSet.noneOf(PlatformAction.class);
        java.util.Collections.addAll(actions, annotation.value());
        return Set.copyOf(actions);
    }

    private static void validateDependencies(Set<EntityCapability> capabilities,
                                             List<? extends StaticCapabilityModule> modules,
                                             Object service) {
        for (StaticCapabilityModule module : modules) {
            if (!capabilities.contains(module.capability())) {
                continue;
            }
            for (EntityCapability dependency : module.dependencies()) {
                if (!capabilities.contains(dependency)) {
                    throw new IllegalStateException("static capability " + module.capability()
                            + " requires " + dependency + " capability: "
                            + (service == null ? "<none>" : service.getClass().getName()));
                }
            }
        }
    }

    private static Set<Class<?>> allInterfaces(Class<?> type) {
        LinkedHashSet<Class<?>> interfaces = new LinkedHashSet<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Class<?> serviceInterface : current.getInterfaces()) {
                collectInterface(serviceInterface, interfaces);
            }
            current = current.getSuperclass();
        }
        return interfaces;
    }

    private static void collectInterface(Class<?> type, Set<Class<?>> interfaces) {
        if (!interfaces.add(type)) {
            return;
        }
        for (Class<?> parent : type.getInterfaces()) {
            collectInterface(parent, interfaces);
        }
    }
}
