package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.PlatformOperation;
import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferencerAbility;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
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
    private static final Set<EntityCapability> SERVICE_DECLARED_CAPABILITIES = Set.of(
            EntityCapability.CRUD,
            EntityCapability.SOFT_DELETE,
            EntityCapability.CACHE,
            EntityCapability.TREE,
            EntityCapability.SORT,
            EntityCapability.REFERENCE,
            EntityCapability.ENABLE,
            EntityCapability.DATA_SCOPE,
            EntityCapability.RECYCLE_BIN,
            EntityCapability.CHILD_RELATION,
            EntityCapability.REFERENCE_DEPENDENCY
    );

    private StaticServiceAbilityCompiler() {
    }

    public static Set<EntityCapability> compile(Object service) {
        EnumSet<EntityCapability> capabilities = EnumSet.noneOf(EntityCapability.class);
        if (service instanceof CrudAbility<?>) capabilities.add(EntityCapability.CRUD);
        if (service instanceof SoftDeleteAbility<?>) capabilities.add(EntityCapability.SOFT_DELETE);
        if (service instanceof CacheAbility<?>) capabilities.add(EntityCapability.CACHE);
        if (CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.TREE,
                net.ximatai.muyun.spring.dynamic.capability.TreeCapabilityModule.class).isEnabledOnStaticService(service)) {
            capabilities.add(EntityCapability.TREE);
        }
        if (CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.SORT,
                net.ximatai.muyun.spring.dynamic.capability.SortCapabilityModule.class).isEnabledOnStaticService(service)) {
            capabilities.add(EntityCapability.SORT);
        }
        if (service instanceof ReferenceAbility<?>) capabilities.add(EntityCapability.REFERENCE);
        if (service instanceof ReferencerAbility<?>) capabilities.add(EntityCapability.REFERENCE_DEPENDENCY);
        if (CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.ENABLE,
                net.ximatai.muyun.spring.dynamic.capability.EnableCapabilityModule.class).isEnabledOnStaticService(service)) {
            capabilities.add(EntityCapability.ENABLE);
        }
        if (service instanceof DataScopeAbility<?>) capabilities.add(EntityCapability.DATA_SCOPE);
        if (CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.RECYCLE_BIN,
                net.ximatai.muyun.spring.dynamic.capability.RecycleBinCapabilityModule.class)
                .isEnabledOnStaticService(service)) capabilities.add(EntityCapability.RECYCLE_BIN);
        if (service instanceof ChildrenAbility<?>) capabilities.add(EntityCapability.CHILD_RELATION);
        return Set.copyOf(capabilities);
    }

    public static boolean isServiceDeclared(EntityCapability capability) {
        return SERVICE_DECLARED_CAPABILITIES.contains(capability);
    }

    public static List<PlatformOperationDefinition> standardOperations(Object service) {
        List<PlatformOperationDefinition> operations = new ArrayList<>();
        if (CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.TREE,
                net.ximatai.muyun.spring.dynamic.capability.TreeCapabilityModule.class).isEnabledOnStaticService(service)) {
            operations.addAll(CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.TREE,
                            net.ximatai.muyun.spring.dynamic.capability.TreeCapabilityModule.class).actions()
                    .staticOperations(CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.SORT,
                            net.ximatai.muyun.spring.dynamic.capability.SortCapabilityModule.class).actions()));
        } else if (CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.SORT,
                net.ximatai.muyun.spring.dynamic.capability.SortCapabilityModule.class).isEnabledOnStaticService(service)) {
            operations.addAll(CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.SORT,
                            net.ximatai.muyun.spring.dynamic.capability.SortCapabilityModule.class).actions()
                    .staticOperations());
        }
        if (CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.ENABLE,
                net.ximatai.muyun.spring.dynamic.capability.EnableCapabilityModule.class).isEnabledOnStaticService(service)) {
            operations.addAll(CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.ENABLE,
                            net.ximatai.muyun.spring.dynamic.capability.EnableCapabilityModule.class).actions()
                    .staticOperations(operationMethods(service)));
        }
        if (CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.RECYCLE_BIN,
                net.ximatai.muyun.spring.dynamic.capability.RecycleBinCapabilityModule.class)
                .isEnabledOnStaticService(service)) {
            boolean purgeEnabled = ((RecycleBinAbility<?>) service).isRecycleBinPurgeEnabled();
            operations.addAll(CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.RECYCLE_BIN,
                            net.ximatai.muyun.spring.dynamic.capability.RecycleBinCapabilityModule.class)
                    .actions().staticOperations(purgeEnabled));
        }
        Set<PlatformAction> disabled = disabledActions(service);
        return operations.stream().filter(operation -> !disabled.contains(operation.action())).toList();
    }

    public static List<PlatformAction> standardActions(Object service) {
        return standardOperations(service).stream()
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
