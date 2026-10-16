package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.platform.support.ModuleActionTestServices;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.platform.support.TestBeanProviders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = DynamicModuleActionConcurrencyRepositoryIT.TestApplication.class)
class DynamicModuleActionConcurrencyRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired PlatformModuleService modules;
    @Autowired PlatformModuleActionService actions;
    @Autowired PausingMetadataService metadata;
    @Autowired ModuleMetadataRelationService relations;
    @Autowired DynamicModuleStandardActionRegistrar registrar;
    @Autowired MetadataFieldService fields;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void shouldRollBackStaleCatalogueInExistingTransaction() throws Exception {
        verifyStaleSnapshotRollsBack(true);
    }

    @Test
    void shouldProvideTransactionForDirectRegistration() throws Exception {
        verifyStaleSnapshotRollsBack(false);
    }

    @Test
    void ordinaryChildFieldChangesKeepTheActionCatalogueInTheSameTransaction() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var module = new PlatformModule();
        module.setAlias("education.child_" + suffix); module.setApplicationAlias("education");
        module.setTitle("子表译文"); module.setModuleKind(ModuleKind.DYNAMIC); modules.insert(module);
        Metadata main = contentModel("main_" + suffix);
        Metadata child = contentModel("child_" + suffix);
        var mainBinding = new ModuleMetadataRelation();
        mainBinding.setModuleAlias(module.getAlias()); mainBinding.setMetadataId(main.getId());
        mainBinding.setRelationRole(RelationRole.MAIN); mainBinding.setTitle("主表"); relations.insert(mainBinding);
        var foreignKey = contentField(child, "issueId"); fields.insert(foreignKey);
        var description = contentField(child, "description"); fields.insert(description);
        var childBinding = new ModuleMetadataRelation();
        childBinding.setModuleAlias(module.getAlias()); childBinding.setMetadataId(child.getId());
        childBinding.setRelationRole(RelationRole.CHILD); childBinding.setParentMetadataId(main.getId());
        childBinding.setForeignKey("issueId"); childBinding.setTitle("子表"); relations.insert(childBinding);
        registrar.register(module);
        assertThat(actions.findByModuleAliasAndActionCode(module.getAlias(), "contentEdit")).isNull();

        var transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            var changed = fields.select(description.getId());
            changed.setContentPolicy(net.ximatai.muyun.spring.common.content.ContentPolicy.MANUAL_TRANSLATIONS);
            fields.update(changed);
            assertContentActions(module, true);
            status.setRollbackOnly();
        });
        assertThat(fields.select(description.getId()).getContentPolicy())
                .isEqualTo(net.ximatai.muyun.spring.common.content.ContentPolicy.KEEP_ORIGINAL);
        assertThat(actions.findByModuleAliasAndActionCode(module.getAlias(), "contentEdit")).isNull();

        var changed = fields.select(description.getId());
        changed.setContentPolicy(net.ximatai.muyun.spring.common.content.ContentPolicy.MANUAL_TRANSLATIONS);
        fields.update(changed);
        assertContentActions(module, true);
        changed = fields.select(description.getId());
        changed.setContentPolicy(net.ximatai.muyun.spring.common.content.ContentPolicy.KEEP_ORIGINAL);
        fields.update(changed);
        assertContentActions(module, false);
        changed = fields.select(description.getId());
        changed.setContentPolicy(net.ximatai.muyun.spring.common.content.ContentPolicy.MANUAL_TRANSLATIONS);
        fields.update(changed);
        assertContentActions(module, true);
        fields.delete(description.getId());
        assertContentActions(module, false);
        fields.restore(description.getId());
        assertContentActions(module, true);
    }

    private Metadata contentModel(String alias) {
        var model = new Metadata(); model.setApplicationAlias("education"); model.setAlias(alias);
        model.setTitle(alias); model.setCapabilityDeclarations(Set.of());
        MetadataCapabilityGovernanceMutationContext.run(() -> metadata.insert(model));
        return model;
    }

    private MetadataField contentField(Metadata model, String name) {
        var field = new MetadataField(); field.setMetadataId(model.getId()); field.setFieldName(name);
        field.setColumnName(name.equals("issueId") ? "issue_id" : name); field.setFieldSpecAlias("string");
        field.setTitle(name); return field;
    }

    private void assertContentActions(PlatformModule module, boolean enabled) {
        for (String action : java.util.List.of("contentEdit", "contentConfirm")) {
            var persisted = actions.findByModuleAliasAndActionCode(module.getAlias(), action);
            assertThat(persisted).as(action).isNotNull();
            assertThat(persisted.getEnabled()).as(action).isEqualTo(enabled);
        }
    }

    private void verifyStaleSnapshotRollsBack(boolean outerTransaction) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        PlatformModule module = new PlatformModule();
        module.setAlias("education.concurrent_" + suffix);
        module.setApplicationAlias("education");
        module.setTitle("并发目录");
        module.setModuleKind(ModuleKind.DYNAMIC);
        modules.insert(module);
        Metadata model = new Metadata();
        model.setApplicationAlias("education");
        model.setAlias("concurrent_" + suffix);
        model.setTitle("并发模型");
        model.setCapabilityDeclarations(Set.of());
        MetadataCapabilityGovernanceMutationContext.run(() -> metadata.insert(model));
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(module.getAlias());
        relation.setMetadataId(model.getId());
        relation.setRelationRole(RelationRole.MAIN);
        relation.setTitle("主模型");
        relations.insert(relation);
        registrar.register(module);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        metadata.pause(model.getId());

        try (var executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "stale-action-snapshot"))) {
            var stale = executor.submit(() -> {
                if (outerTransaction) transactions.executeWithoutResult(status -> registrar.register(module));
                else registrar.register(module);
            });
            try {
                assertThat(metadata.snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
                transactions.executeWithoutResult(status -> {
                    Metadata latest = metadata.select(model.getId());
                    latest.setCapabilityDeclarations(Set.of("ENABLE"));
                    MetadataCapabilityGovernanceMutationContext.run(() -> metadata.update(latest));
                    registrar.register(modules.select(module.getAlias()));
                });
            } finally {
                metadata.continueRegistration.countDown();
            }
            assertThatThrownBy(() -> stale.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(OptimisticLockException.class);
        }

        assertThat(metadata.select(model.getId()).getCapabilityDeclarations()).containsExactly("ENABLE");
        assertThat(actions.findByModuleAliasAndActionCode(module.getAlias(), "enable").getEnabled()).isTrue();
        assertThat(actions.findByModuleAliasAndActionCode(module.getAlias(), "disable").getEnabled()).isTrue();
    }

    static class PausingMetadataService extends MetadataService {
        private volatile String pausedId;
        private final AtomicBoolean waiting = new AtomicBoolean();
        private CountDownLatch snapshotRead;
        private CountDownLatch continueRegistration;

        PausingMetadataService(MetadataDao dao) { super(dao,
                TestBeanProviders.empty(PlatformMetadataSchemaEnsureService.class),
                Optional.empty(),
                TestBeanProviders.empty(ConfigurationReferenceDeletionGuard.class),
                TestBeanProviders.empty(ModuleMetadataRelationService.class), event -> {}); }

        void pause(String id) {
            pausedId = id;
            snapshotRead = new CountDownLatch(1);
            continueRegistration = new CountDownLatch(1);
            waiting.set(true);
        }

        @Override
        public Metadata select(String id) {
            Metadata snapshot = super.select(id);
            if (id.equals(pausedId) && "stale-action-snapshot".equals(Thread.currentThread().getName())
                    && waiting.compareAndSet(true, false)) {
                snapshotRead.countDown();
                try {
                    if (!continueRegistration.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("snapshot wait timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return snapshot;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = {PlatformModuleDao.class, MetadataDao.class})
    static class TestApplication {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean PlatformModuleService modules(PlatformModuleDao dao) { return new PlatformModuleService(dao, event -> {}); }
        @Bean PlatformModuleActionService actions(PlatformModuleActionDao dao, PlatformModuleService modules) {
            return ModuleActionTestServices.withDeclaredDataPolicy(dao, modules);
        }
        @Bean ModuleActionContributionRegistrar contributions(PlatformModuleActionService actions) {
            return new ModuleActionContributionRegistrar(actions);
        }
        @Bean PausingMetadataService metadata(MetadataDao dao) { return new PausingMetadataService(dao); }
        @Bean ModuleMetadataRelationService relations(ModuleMetadataRelationDao dao, PlatformModuleService modules,
                                                      MetadataService metadata, ObjectProvider<MetadataFieldService> fields) {
            return new ModuleMetadataRelationService(
                    dao,
                    modules,
                    metadata,
                    Optional.empty(),
                    TestBeanProviders.empty(ConfigurationReferenceDeletionGuard.class),
                    fields,
                    event -> {});
        }
        @Bean FieldSpecService specs() {
            var service = new FieldSpecService(new net.ximatai.muyun.spring.platform.support.TestMemoryDao<>(),
                    new net.ximatai.muyun.spring.platform.support.TestMemoryDao<>());
            var spec = new FieldSpec(); spec.setAlias("string"); spec.setDefaultLength(128); service.insert(spec);
            return service;
        }
        @Bean MetadataFieldConfigService fieldConfigs() { return org.mockito.Mockito.mock(MetadataFieldConfigService.class); }
        @Bean MetadataFieldService fields(MetadataFieldDao dao, MetadataService metadata, FieldSpecService specs,
                ObjectProvider<ModuleMetadataRelationService> relations, ObjectProvider<PlatformModuleService> modules,
                ObjectProvider<MetadataFieldConfigService> configs, org.springframework.context.ApplicationEventPublisher events) {
            return new MetadataFieldService(dao, metadata, specs,
                    TestBeanProviders.empty(net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator.class),
                    TestBeanProviders.empty(PlatformMetadataSchemaEnsureService.class),
                    TestBeanProviders.empty(ConfigurationReferenceDeletionGuard.class), relations, modules,
                    TestBeanProviders.empty(MetadataFieldReferenceConfigService.class), configs, events);
        }
        @Bean DynamicModuleStandardActionRegistrar registrar(PlatformModuleService modules,
                ModuleActionContributionRegistrar contributions, ObjectProvider<ModuleMetadataRelationService> relations,
                ObjectProvider<MetadataService> metadata, ObjectProvider<MetadataFieldService> fields,
                PlatformTransactionManager transactionManager) {
            return new DynamicModuleStandardActionRegistrar(modules, contributions, relations, metadata, fields, transactionManager);
        }
    }
}
