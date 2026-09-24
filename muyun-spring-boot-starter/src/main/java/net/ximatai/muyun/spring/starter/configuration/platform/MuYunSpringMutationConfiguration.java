package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleListener;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

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
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<DataSource> dataSource) {
        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        MutationTransactionOperator operator = manager == null
                ? MutationTransactionOperator.NONE
                : transactionOperator(manager, dataSource.getIfAvailable());
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

    private static MutationTransactionOperator transactionOperator(PlatformTransactionManager manager, DataSource dataSource) {
        TransactionTemplate mutation = new TransactionTemplate(manager);
        TransactionTemplate statement = new TransactionTemplate(manager);
        statement.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return new MutationTransactionOperator() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> work) {
                return mutation.execute(status -> work.get());
            }

            @Override
            public <T> T executeStatement(java.util.function.Supplier<T> work) {
                return statement.execute(status -> work.get());
            }

            @Override
            public void lock(String scope, String key) {
                if (dataSource == null || !TransactionSynchronizationManager.isActualTransactionActive()
                        || !TransactionSynchronizationManager.hasResource(dataSource)) {
                    throw new IllegalStateException("mutation locks require a database-bound transaction");
                }
                new JdbcTemplate(dataSource).execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                    if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                        throw new IllegalStateException("transactional mutation locks currently require PostgreSQL");
                    }
                    if (connection.getTransactionIsolation() != java.sql.Connection.TRANSACTION_READ_COMMITTED) {
                        throw new IllegalStateException("mutation partition locks require READ_COMMITTED isolation");
                    }
                    try (var lock = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                        lock.setString(1, scope.length() + ":" + scope + ":" + key);
                        lock.execute();
                    }
                    return null;
                });
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
