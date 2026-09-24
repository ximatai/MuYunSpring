package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleListener;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写入链路装配：把事务执行器和删除日志安装到 Ability 运行时，
 * 使领域 Service 无需感知日志持久化或 Spring 事务 API。
 */
@Configuration(proxyBeanMethods = false)
public class MuYunSpringMutationConfiguration {
    @Bean
    /** 注入删除生命周期监听器；应用未提供时使用显式空实现。 */
    DeletionLifecycleListenerRegistration deletionLifecycleListenerRegistration(
            ObjectProvider<DeletionLifecycleListener> listenerProvider) {
        return new DeletionLifecycleListenerRegistration(
                listenerProvider.getIfAvailable(() -> DeletionLifecycleListener.NONE));
    }

    @Bean
    /** 有事务管理器时让标准写入、子表和生命周期共享事务；无事务宿主保持可用。 */
    MutationTransactionRegistration mutationTransactionRegistration(
            ObjectProvider<PlatformTransactionManager> transactionManager) {
        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        MutationTransactionOperator operator = manager == null
                ? MutationTransactionOperator.NONE
                : transactionOperator(new TransactionTemplate(manager));
        return new MutationTransactionRegistration(operator);
    }

    static final class DeletionLifecycleListenerRegistration implements DisposableBean {
        DeletionLifecycleListenerRegistration(DeletionLifecycleListener listener) {
            PlatformAbilityRuntime.configureDeletionLifecycleListener(listener);
        }

        @Override
        public void destroy() {
            PlatformAbilityRuntime.resetDeletionLifecycleListener();
        }
    }

    private static MutationTransactionOperator transactionOperator(TransactionTemplate transactionTemplate) {
        return new MutationTransactionOperator() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> work) {
                return transactionTemplate.execute(status -> work.get());
            }
        };
    }

    static final class MutationTransactionRegistration implements DisposableBean {
        MutationTransactionRegistration(MutationTransactionOperator operator) {
            PlatformAbilityRuntime.configureMutationTransactionOperator(operator);
        }

        @Override
        public void destroy() {
            PlatformAbilityRuntime.resetMutationTransactionOperator();
        }
    }
}
