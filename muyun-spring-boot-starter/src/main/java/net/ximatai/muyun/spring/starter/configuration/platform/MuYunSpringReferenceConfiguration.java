package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceDeletionGuard;
import net.ximatai.muyun.spring.ability.reference.CompositeReferenceDeletionGuard;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import net.ximatai.muyun.spring.ability.reference.ReferencedByResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadFacade;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadObserver;
import net.ximatai.muyun.spring.ability.child.ChildAbilityResolver;
import net.ximatai.muyun.spring.platform.reference.DynamicReferenceDeletionGuard;
import net.ximatai.muyun.spring.platform.reference.PlatformReferenceTargetResolver;
import net.ximatai.muyun.spring.platform.reference.PlatformReferencedByResolver;
import net.ximatai.muyun.spring.platform.reference.PlatformReferenceLoadResolver;
import net.ximatai.muyun.spring.platform.reference.StaticReferenceDeletionGuard;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.platform.reference.PlatformChildAbilityResolver;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 引用与父子能力装配：从静态 Ability 目录和可选动态运行时编译解析器，
 * 再以可复位注册方式安装到统一 Ability 链路。
 */
@Configuration(proxyBeanMethods = false)
public class MuYunSpringReferenceConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(MuYunSpringReferenceConfiguration.class);

    @Bean
    /** 收集静态 CRUD Ability，作为引用、反向引用和子表解析的共同事实来源。 */
    StaticAbilityCatalog staticAbilityCatalog(List<CrudAbility<?>> abilities) {
        return new StaticAbilityCatalog(abilities);
    }

    @Bean
    /** 根据静态引用完整性声明构造删除保护器。 */
    ReferenceDeletionGuard staticReferenceDeletionGuard(StaticAbilityCatalog abilities) {
        return new StaticReferenceDeletionGuard(abilities.abilities());
    }

    @Bean
    /** 动态运行时存在时追加动态引用删除保护；否则显式为空实现。 */
    ReferenceDeletionGuard dynamicReferenceDeletionGuard(ObjectProvider<DynamicRecordRuntime> runtime) {
        DynamicRecordRuntime value = runtime.getIfAvailable();
        return value == null ? ReferenceDeletionGuard.NONE : new DynamicReferenceDeletionGuard(value);
    }

    @Bean
    /** 合并静态与动态删除保护，并注册到统一删除前置校验链。 */
    ReferenceDeletionGuardRegistration referenceDeletionGuardRegistration(
            ObjectProvider<ReferenceDeletionGuard> guardProvider) {
        return new ReferenceDeletionGuardRegistration(
                new CompositeReferenceDeletionGuard(guardProvider.orderedStream().toList()));
    }

    @Bean
    /** 将静态及动态目标解析规则安装到引用候选与标题投影链路。 */
    ReferenceTargetResolverRegistration referenceTargetResolverRegistration(
            StaticAbilityCatalog staticAbilities,
            ObjectProvider<DynamicRecordRuntime> dynamicRuntime) {
        return new ReferenceTargetResolverRegistration(new PlatformReferenceTargetResolver(
                staticAbilities, dynamicRuntime.getIfAvailable()));
    }

    @Bean
    /** 注册反向引用解析器，为 {@code @ReferencedBy} 提供运行时装配能力。 */
    ReferencedByResolverRegistration referencedByResolverRegistration(StaticAbilityCatalog abilities) {
        return new ReferencedByResolverRegistration(new PlatformReferencedByResolver(abilities));
    }

    @Bean
    /** 提供静态实体声明的引用读事实解析器。 */
    ReferenceLoadResolver referenceLoadResolver(StaticAbilityCatalog abilities) {
        return new PlatformReferenceLoadResolver(abilities);
    }

    @Bean
    /** 向领域 read facade 暴露已声明读事实的批量 enrich 边界。 */
    ReferenceReadFacade referenceReadFacade(ReferenceLoadResolver resolver) {
        return new ReferenceReadFacade(resolver);
    }

    @Bean
    /** 可选注册引用读观测器；观测异常只记录，不得阻断正常的引用读取。 */
    ReferenceReadObserverRegistration referenceReadObserverRegistration(
            ObjectProvider<ReferenceReadObserver> observerProvider) {
        List<ReferenceReadObserver> observers = observerProvider.orderedStream().toList();
        ReferenceReadObserver composite = observers.isEmpty()
                ? ReferenceReadObserver.NONE
                : request -> observers.forEach(observer -> observe(observer, request));
        return new ReferenceReadObserverRegistration(composite);
    }

    private static void observe(ReferenceReadObserver observer, ReferenceReadObserver.ProjectionRequest request) {
        try {
            observer.onProjection(request);
        } catch (RuntimeException exception) {
            LOGGER.warn("Reference read observer failed and was ignored", exception);
        }
    }

    @Bean
    /** 注册多跳引用字段加载解析器，静态与动态路径共享其投影语义。 */
    ReferenceLoadResolverRegistration referenceLoadResolverRegistration(ReferenceLoadResolver resolver) {
        return new ReferenceLoadResolverRegistration(resolver);
    }

    @Bean
    /** 注册子表 Ability 解析器，使父子聚合不依赖 Service 手工连接。 */
    ChildAbilityResolverRegistration childAbilityResolverRegistration(StaticAbilityCatalog abilities) {
        return new ChildAbilityResolverRegistration(new PlatformChildAbilityResolver(abilities));
    }

    static final class ReferenceDeletionGuardRegistration implements DisposableBean {
        ReferenceDeletionGuardRegistration(ReferenceDeletionGuard guard) {
            PlatformAbilityRuntime.configureReferenceDeletionGuard(guard);
        }

        @Override
        public void destroy() {
            PlatformAbilityRuntime.resetReferenceDeletionGuard();
        }
    }

    static final class ReferenceTargetResolverRegistration implements DisposableBean {
        ReferenceTargetResolverRegistration(ReferenceTargetResolver resolver) {
            PlatformAbilityRuntime.configureReferenceTargetResolver(resolver);
        }

        @Override
        public void destroy() {
            PlatformAbilityRuntime.resetReferenceTargetResolver();
        }
    }

    static final class ReferencedByResolverRegistration implements DisposableBean {
        ReferencedByResolverRegistration(ReferencedByResolver resolver) {
            PlatformAbilityRuntime.configureReferencedByResolver(resolver);
        }

        @Override
        public void destroy() {
            PlatformAbilityRuntime.resetReferencedByResolver();
        }
    }

    static final class ReferenceLoadResolverRegistration implements DisposableBean {
        ReferenceLoadResolverRegistration(ReferenceLoadResolver resolver) {
            PlatformAbilityRuntime.configureReferenceLoadResolver(resolver);
        }

        @Override
        public void destroy() {
            PlatformAbilityRuntime.resetReferenceLoadResolver();
        }
    }

    static final class ReferenceReadObserverRegistration implements DisposableBean {
        ReferenceReadObserverRegistration(ReferenceReadObserver observer) {
            PlatformAbilityRuntime.configureReferenceReadObserver(observer);
        }

        @Override
        public void destroy() {
            PlatformAbilityRuntime.resetReferenceReadObserver();
        }
    }

    static final class ChildAbilityResolverRegistration implements DisposableBean {
        ChildAbilityResolverRegistration(ChildAbilityResolver resolver) {
            PlatformAbilityRuntime.configureChildAbilityResolver(resolver);
        }

        @Override
        public void destroy() {
            PlatformAbilityRuntime.resetChildAbilityResolver();
        }
    }
}
