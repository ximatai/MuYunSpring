package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MetadataStandardActionContractTest {
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    private final PlatformModuleService modules = new PlatformModuleService(new TestMemoryDao<>());
    private final PlatformModuleActionService actions = new PlatformModuleActionService(new TestMemoryDao<>(), modules);
    private DynamicModuleStandardActionRegistrar registrar;
    private final ApplicationEventPublisher events = event -> {
        if (registrar != null && event instanceof MetadataChangedEvent changed) registrar.reconcile(changed);
    };
    private final MetadataService metadata = new MetadataService(new TestMemoryDao<>(),
            beans.getBeanProvider(PlatformMetadataSchemaEnsureService.class), Optional.empty(),
            beans.getBeanProvider(ConfigurationReferenceDeletionGuard.class),
            beans.getBeanProvider(ModuleMetadataRelationService.class), events);
    private final ModuleMetadataRelationService relations = new ModuleMetadataRelationService(new TestMemoryDao<>(),
            modules, metadata, Optional.empty(), beans.getBeanProvider(ConfigurationReferenceDeletionGuard.class),
            beans.getBeanProvider(MetadataFieldService.class), events);

    MetadataStandardActionContractTest() {
        beans.addBean("metadata", metadata);
        beans.addBean("relations", relations);
        beans.addBean("fields", new MetadataFieldService(new TestMemoryDao<>(), metadata, mock(FieldSpecService.class)));
        registrar = new DynamicModuleStandardActionRegistrar(modules, new ModuleActionContributionRegistrar(actions),
                beans.getBeanProvider(ModuleMetadataRelationService.class), beans.getBeanProvider(MetadataService.class),
                beans.getBeanProvider(MetadataFieldService.class));
    }

    @Test
    void shouldIgnoreTenantOwnedMetadataAndRelationFacts() {
        Metadata model = main(Set.of("ENABLE"));
        ModuleActionContributionRegistrar contributions = mock(ModuleActionContributionRegistrar.class);
        DynamicModuleStandardActionRegistrar isolated = new DynamicModuleStandardActionRegistrar(modules, contributions,
                beans.getBeanProvider(ModuleMetadataRelationService.class), beans.getBeanProvider(MetadataService.class),
                beans.getBeanProvider(MetadataFieldService.class));
        isolated.reconcile(new MetadataChangedEvent(model.getId(), null, "tenant-a"));
        isolated.reconcile(new MetadataChangedEvent(model.getId(), "education.project", "tenant-a"));
        org.mockito.Mockito.verifyNoInteractions(contributions);
    }

    @Test
    void shouldSynchronizeDirectGovernedMetadataChangesDespiteRuntimeSuppression() {
        Metadata model = main(Set.of());
        updateCapabilities(model, Set.of("ENABLE"));
        assertThat(actions.findByModuleAliasAndActionCode("education.project", "enable").getEnabled()).isTrue();

        updateCapabilities(model, Set.of());
        assertThat(actions.findByModuleAliasAndActionCode("education.project", "enable").getEnabled()).isFalse();
        assertThat(actions.findByModuleAliasAndActionCode("education.project", "disable").getEnabled()).isFalse();
        assertThat(modules.select("education.project").getMainCapabilityDeclarations()).containsExactly("TREE");
    }

    @Test
    void shouldRestoreFromMainMetadataRatherThanStaleModuleIntent() {
        main(Set.of("ENABLE"));
        registrar.run();
        assertThat(actions.findByModuleAliasAndActionCode("education.project", "enable").getEnabled()).isTrue();
        assertThat(actions.findByModuleAliasAndActionCode("education.project", "tree")).isNull();
    }

    @Test
    void shouldRestoreCallerTenantAfterResolvingGlobalMetadataFacts() {
        Metadata model = main(Set.of("ENABLE"));
        try (TenantContext.Scope ignored = TenantContext.use("tenant-user")) {
            registrar.reconcile(new MetadataChangedEvent(model.getId(), null));
            assertThat(TenantContext.currentTenantId()).contains("tenant-user");
        }
        assertThat(actions.findByModuleAliasAndActionCode("education.project", "enable").getEnabled()).isTrue();
    }

    @Test
    void shouldFallBackToNormalizedIntentWhenMainRelationIsRemoved() {
        main(Set.of());
        relations.delete(relations.list(Criteria.of()).getFirst().getId());
        assertThat(actions.findByModuleAliasAndActionCode("education.project", "tree").getEnabled()).isTrue();
        assertThat(actions.findByModuleAliasAndActionCode("education.project", "sort").getEnabled()).isTrue();
    }

    @Test
    void shouldNotRewriteUnchangedCatalogueDuringReconciliation() {
        Metadata model = main(Set.of("ENABLE"));
        Integer version = actions.findByModuleAliasAndActionCode("education.project", "enable").getVersion();
        registrar.reconcile(new MetadataChangedEvent(model.getId(), null));
        assertThat(actions.findByModuleAliasAndActionCode("education.project", "enable").getVersion()).isEqualTo(version);
    }

    private Metadata main(Set<String> capabilities) {
        PlatformModule module = new PlatformModule();
        module.setAlias("education.project");
        module.setApplicationAlias("education");
        module.setTitle("项目");
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setMainCapabilityDeclarations(Set.of("TREE"));
        modules.insert(module);
        Metadata model = new Metadata();
        model.setAlias("project");
        model.setApplicationAlias("education");
        model.setTitle("项目");
        model.setCapabilityDeclarations(capabilities);
        MetadataCapabilityGovernanceMutationContext.run(() -> metadata.insert(model));
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(module.getAlias());
        relation.setMetadataId(model.getId());
        relation.setRelationRole(RelationRole.MAIN);
        relations.insert(relation);
        return model;
    }

    private void updateCapabilities(Metadata model, Set<String> capabilities) {
        model.setCapabilityDeclarations(capabilities);
        MetadataCapabilityGovernanceMutationContext.run(() -> metadata.update(model));
    }
}
