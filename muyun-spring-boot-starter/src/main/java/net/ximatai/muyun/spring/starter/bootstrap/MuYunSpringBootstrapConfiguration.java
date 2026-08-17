package net.ximatai.muyun.spring.starter.bootstrap;

import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.starter.configuration.platform.MuYunSpringStaticDeclarationConfiguration;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryInitialDataDeclarations;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryFieldInitialDataDeclarationProvider;
import net.ximatai.muyun.spring.platform.dictionary.PlatformTimeZoneDictionaryInitialDataDeclarationProvider;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataBootstrapTask;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataDeclarationProvider;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.metadata.PlatformFieldCatalogInitialDataDeclarationProvider;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import net.ximatai.muyun.spring.platform.web.PlatformMenuInitialDataDeclarationProvider;
import net.ximatai.muyun.spring.platform.web.PlatformMenuContributionReconciliationTask;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinition;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

/**
 * 平台启动编排配置：将静态声明、初始数据等 {@link PlatformBootstrapTask} 统一收集，
 * 在 Spring 上下文就绪后按确定顺序写入平台托管状态。
 */
@Configuration(proxyBeanMethods = false)
@Import(MuYunSpringStaticDeclarationConfiguration.class)
public class MuYunSpringBootstrapConfiguration {
    @Bean
    @ConditionalOnMissingBean(InitialDataExecutor.class)
    /** 汇集各领域的初始数据能力与声明提供者，避免由具体领域直接编排启动顺序。 */
    InitialDataExecutor initialDataExecutor(List<InitialDataAbility<?>> abilities,
                                            List<InitialDataDeclarationProvider> providers) {
        return new InitialDataExecutor(abilities, providers);
    }

    @Bean
    @ConditionalOnMissingBean(PlatformBootstrapRunner.class)
    /** 注册唯一的启动任务执行器，确保任务按 order 和名称稳定排序。 */
    PlatformBootstrapRunner platformBootstrapRunner(List<PlatformBootstrapTask> tasks) {
        return new PlatformBootstrapRunner(tasks);
    }

    @Bean
    @ConditionalOnMissingBean(InitialDataBootstrapTask.class)
    /** 将初始数据执行纳入统一启动任务链，而不是作为配置类副作用立即执行。 */
    InitialDataBootstrapTask initialDataBootstrapTask(InitialDataExecutor initialDataExecutor) {
        return new InitialDataBootstrapTask(initialDataExecutor);
    }

    @Bean
    @ConditionalOnMissingBean(PlatformMenuInitialDataDeclarationProvider.class)
    /** 将平台静态菜单编译为初始数据声明，交由统一执行器落库。 */
    PlatformMenuInitialDataDeclarationProvider platformMenuInitialDataDeclarationProvider(
            MenuService menuService,
            ApplicationContext applicationContext) {
        return new PlatformMenuInitialDataDeclarationProvider(menuService, applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean(PlatformMenuContributionReconciliationTask.class)
    /** Disables code-owned system menus that are no longer declared before tenant copies are reconciled. */
    PlatformMenuContributionReconciliationTask platformMenuContributionReconciliationTask(
            MenuService menuService,
            PlatformMenuInitialDataDeclarationProvider menuDeclarations) {
        return new PlatformMenuContributionReconciliationTask(menuService, menuDeclarations);
    }

    @Bean
    @ConditionalOnMissingBean(DictionaryFieldInitialDataDeclarationProvider.class)
    /** 将静态字段声明的字典 baseline 转换为初始数据，不让领域模型感知启动或持久化。 */
    DictionaryFieldInitialDataDeclarationProvider dictionaryFieldInitialDataDeclarationProvider(
            DictionaryInitialDataDeclarations dictionaryInitialDataDeclarations,
            StaticModuleDefinitionCatalog staticModuleDefinitions) {
        List<Class<?>> modelClasses = staticModuleDefinitions.definitions().stream()
                .map(StaticModuleDefinition::modelClass)
                .filter(modelClass -> modelClass != null && modelClass != Object.class)
                .toList();
        return new DictionaryFieldInitialDataDeclarationProvider(dictionaryInitialDataDeclarations, modelClasses);
    }

    @Bean
    @ConditionalOnMissingBean(DictionaryInitialDataDeclarations.class)
    /** 提供字典初始数据的聚合入口，供平台与领域声明共同注册。 */
    DictionaryInitialDataDeclarations dictionaryInitialDataDeclarations(DictionaryCategoryService categoryService,
                                                                        DictionaryItemService itemService) {
        return new DictionaryInitialDataDeclarations(categoryService, itemService);
    }

    @Bean
    @ConditionalOnMissingBean(PlatformTimeZoneDictionaryInitialDataDeclarationProvider.class)
    /** Registers the IANA time-zone baseline as platform data shared by every application. */
    PlatformTimeZoneDictionaryInitialDataDeclarationProvider platformTimeZoneDictionaryInitialDataDeclarationProvider(
            DictionaryInitialDataDeclarations dictionaryInitialDataDeclarations) {
        return new PlatformTimeZoneDictionaryInitialDataDeclarationProvider(dictionaryInitialDataDeclarations);
    }

    @Bean
    @ConditionalOnBean({FieldSpecService.class, FieldUiControlService.class,
            FieldUiControlPropertyService.class, FieldUiControlBindingService.class})
    @ConditionalOnMissingBean(PlatformFieldCatalogInitialDataDeclarationProvider.class)
    PlatformFieldCatalogInitialDataDeclarationProvider platformFieldCatalogInitialDataDeclarationProvider(
            FieldSpecService fieldTypes,
            FieldUiControlService uiTypes,
            FieldUiControlPropertyService attributes,
            FieldUiControlBindingService mappings) {
        return new PlatformFieldCatalogInitialDataDeclarationProvider(fieldTypes, uiTypes, attributes, mappings);
    }
}
