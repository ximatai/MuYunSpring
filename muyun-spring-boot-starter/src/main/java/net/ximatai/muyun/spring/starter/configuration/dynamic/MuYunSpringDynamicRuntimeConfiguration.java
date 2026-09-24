package net.ximatai.muyun.spring.starter.configuration.dynamic;

import net.ximatai.muyun.spring.starter.configuration.database.MuYunSpringDatabaseValueConversionConfiguration;
import net.ximatai.muyun.spring.starter.configuration.runtime.MuYunSpringRuntimeConfiguration;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.DatabaseValueConverter;
import net.ximatai.muyun.spring.ability.event.RuntimeEventListener;
import net.ximatai.muyun.spring.ability.event.RuntimeEventMulticaster;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeModeProvider;
import net.ximatai.muyun.spring.common.schema.PlatformSchemaMigrationPolicy;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutorRegistry;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionTransactionOperator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicFieldValueValidator;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicModuleRegistry;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicOptionLoadPopulator;
import net.ximatai.muyun.spring.dynamic.runtime.OptionSourceDynamicOptionLoadPopulator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceDependencyScopeResolver;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordMutationCoordinator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordMutationCoordinators;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.dynamic.schema.DynamicTableMapper;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryFieldValueValidator;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * 动态运行时装配：把动态元数据、记录运行时、Schema、动作、时间和事件接入
 * 与静态业务相同的数据库、权限、字段保护和生命周期基础能力。
 */
@Configuration(proxyBeanMethods = false)
@Import({MuYunSpringDatabaseValueConversionConfiguration.class, MuYunSpringRuntimeConfiguration.class})
public class MuYunSpringDynamicRuntimeConfiguration {
    @Bean
    @ConditionalOnBean(DictionaryItemService.class)
    @ConditionalOnMissingBean(DynamicFieldValueValidator.class)
    /** 字典能力存在时，动态字段值校验复用平台字典，而不是自行维护枚举。 */
    DynamicFieldValueValidator dictionaryFieldValueValidator(DictionaryItemService itemService) {
        return new DictionaryFieldValueValidator(itemService);
    }

    @Bean
    @ConditionalOnMissingBean
    /** 未接入任何字段校验器时保留空实现，保证动态运行时可按能力组合启动。 */
    DynamicFieldValueValidator dynamicFieldValueValidator() {
        return DynamicFieldValueValidator.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    /** 在保存和刷新前校验动态模块定义，防止无效元数据进入运行态。 */
    ModuleDefinitionValidator moduleDefinitionValidator() {
        return new ModuleDefinitionValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    /** 将动态表映射和运行模式下的迁移策略组合为 Schema 服务。 */
    DynamicSchemaService dynamicSchemaService(IDatabaseOperations<?> operations,
                                              ModuleDefinitionValidator moduleDefinitionValidator,
                                              PlatformRuntimeModeProvider runtimeModeProvider) {
        return new DynamicSchemaService(operations, new DynamicTableMapper(), moduleDefinitionValidator,
                new PlatformSchemaMigrationPolicy(runtimeModeProvider));
    }

    @Bean
    @ConditionalOnMissingBean
    /** 构建单一动态记录运行时，集中承载字段保护、动作和时间等可组合能力。 */
    DynamicRecordRuntime dynamicRecordRuntime(IDatabaseOperations<?> operations,
                                              DynamicFieldValueValidator fieldValueValidator,
                                              RuntimeEventPublisher eventPublisher,
                                              DynamicActionExecutorRegistry actionExecutorRegistry,
                                              DynamicActionTransactionOperator actionTransactionOperator,
                                              ObjectProvider<FieldCryptoProvider> fieldCryptoProvider,
                                              ObjectProvider<FieldSigner> fieldSigner,
                                              PlatformTimeService platformTimeService,
                                              DatabaseValueConverter databaseValueConverter,
                                              ObjectProvider<OptionSourceRegistry> optionSourceRegistry) {
        OptionSourceRegistry registry = optionSourceRegistry.getIfAvailable();
        return DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(fieldValueValidator)
                .eventPublisher(eventPublisher)
                .actionExecutorRegistry(actionExecutorRegistry)
                .actionTransactionOperator(actionTransactionOperator)
                .fieldProtection(
                        fieldCryptoProvider.getIfAvailable(() -> FieldCryptoProvider.UNAVAILABLE),
                        fieldSigner.getIfAvailable(() -> FieldSigner.UNAVAILABLE))
                .timeService(platformTimeService)
                .valueConverter(databaseValueConverter)
                .optionLoadPopulator(registry == null
                        ? DynamicOptionLoadPopulator.NONE
                        : new OptionSourceDynamicOptionLoadPopulator(registry))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    /** 动态记录对外门面复用动作权限、数据范围与写入协调器。 */
    DynamicRecordService dynamicRecordService(DynamicRecordRuntime runtime,
                                              ObjectProvider<ActionExecutionPolicyService> actionExecutionPolicyService,
                                              ObjectProvider<DataScopeCriteriaService> dataScopeCriteriaService,
                                              ObjectProvider<DynamicRecordMutationCoordinator> mutationCoordinator) {
        return new DynamicRecordService(runtime,
                actionExecutionPolicyService.getIfAvailable(AllowAllActionExecutionPolicyService::new),
                dataScopeCriteriaService.getIfAvailable(AllowAllDataScopeCriteriaService::new),
                DynamicRecordMutationCoordinators.lazyComposite(() -> mutationCoordinator.orderedStream().toList()));
    }

    @Bean
    @ConditionalOnMissingBean
    /** 为动态引用依赖提供作用域解析，保持与静态引用失效的语义一致。 */
    DynamicReferenceDependencyScopeResolver dynamicReferenceDependencyScopeResolver(DynamicRecordRuntime runtime) {
        return new DynamicReferenceDependencyScopeResolver(runtime);
    }

    @Bean
    @ConditionalOnMissingBean
    /** 延迟读取动作执行器，允许不同领域以 Bean 方式贡献动态动作。 */
    DynamicActionExecutorRegistry dynamicActionExecutorRegistry(ObjectProvider<DynamicActionExecutor> executors) {
        return new DynamicActionExecutorRegistry(() -> executors.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    /** 有事务管理器时包裹动态动作；轻量宿主则显式使用无事务实现。 */
    DynamicActionTransactionOperator dynamicActionTransactionOperator(
            ObjectProvider<PlatformTransactionManager> transactionManager) {
        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        if (manager == null) {
            return DynamicActionTransactionOperator.none();
        }
        TransactionTemplate transactionTemplate = new TransactionTemplate(manager);
        return (context, action) -> transactionTemplate.execute(status -> action.get());
    }

    @Bean
    @ConditionalOnMissingBean
    /** 将领域运行事件汇聚为多播器，避免动态运行时直接依赖具体监听器。 */
    RuntimeEventPublisher runtimeEventPublisher(ObjectProvider<RuntimeEventListener> listeners) {
        return new RuntimeEventMulticaster(() -> listeners.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    /** 统一模块刷新入口，使元数据、Schema 与运行时快照按同一链路切换。 */
    DynamicModuleRuntimeRefresher dynamicModuleRuntimeRefresher(DynamicSchemaService schemaService,
                                                  DynamicRecordRuntime runtime) {
        return new DynamicModuleRuntimeRefresher(schemaService, runtime);
    }
}
