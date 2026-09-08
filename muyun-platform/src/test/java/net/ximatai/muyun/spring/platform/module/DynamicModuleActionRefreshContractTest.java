package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamicModuleActionRefreshContractTest {
    private final PlatformModuleService modules = new PlatformModuleService(new TestMemoryDao<>());
    private final PlatformDynamicRuntimeRefreshService runtime = mock(PlatformDynamicRuntimeRefreshService.class);
    private final ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
    private final PlatformDynamicRuntimeRefreshCoordinator coordinator = new PlatformDynamicRuntimeRefreshCoordinator(
            runtime, relations, mock(ModuleMetadataFieldService.class), mock(MetadataViewService.class));
    private final PlatformModuleActionService actions = new PlatformModuleActionService(new TestMemoryDao<>(),
            modules, Optional.of(coordinator));
    private final DynamicModuleStandardActionRegistrar registrar = new DynamicModuleStandardActionRegistrar(modules,
            new ModuleActionContributionRegistrar(actions));
    private final TransactionTemplate transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    });

    @Test
    void shouldRequireGlobalMainMetadataBeforeActivatingGlobalActions() {
        TestMemoryDao<ModuleMetadataRelation> dao = new TestMemoryDao<>();
        ModuleMetadataRelation main = new ModuleMetadataRelation();
        main.setId("main-relation");
        main.setModuleAlias("education.project");
        main.setRelationRole(net.ximatai.muyun.spring.platform.metadata.RelationRole.MAIN);
        main.setTenantId("tenant-owner");
        main.setDeleted(false);
        dao.insert(main);
        ModuleMetadataRelationService scopedRelations = new ModuleMetadataRelationService(dao, modules,
                mock(net.ximatai.muyun.spring.platform.metadata.MetadataService.class));
        var scopedCoordinator = new PlatformDynamicRuntimeRefreshCoordinator(runtime, scopedRelations,
                mock(ModuleMetadataFieldService.class), mock(MetadataViewService.class));

        scopedCoordinator.refreshConfiguredModule(main.getModuleAlias());
        verifyNoInteractions(runtime);
        main.setTenantId(null);
        scopedCoordinator.refreshConfiguredModule(main.getModuleAlias());
        verify(runtime).activateNow(main.getModuleAlias());
    }

    @Test
    void shouldActivateOnceForMetadataAndModuleCallbacksAndResetForTheNextTransaction() {
        PlatformModule module = module();
        ModuleMetadataRelation main = new ModuleMetadataRelation();
        main.setModuleAlias(module.getAlias());
        when(relations.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(main));
        when(relations.list(any(Criteria.class), any(PageRequest.class),
                any(net.ximatai.muyun.database.core.orm.Sort.class))).thenReturn(List.of(main));

        for (int transaction = 0; transaction < 2; transaction++) {
            transactions.executeWithoutResult(status -> {
                net.ximatai.muyun.spring.ability.TransactionScopeSupport.afterCommitOrNow(
                        () -> coordinator.activateByMetadataIdNow("main-metadata"));
                coordinator.refreshConfiguredModule(module.getAlias());
                coordinator.refreshConfiguredModule(module.getAlias());
                net.ximatai.muyun.spring.ability.TransactionScopeSupport.afterCommitOrNow(
                        () -> coordinator.activateModulesNow(List.of(module.getAlias(), "education.other")));
            });
        }

        verify(runtime, times(2)).activateNow(module.getAlias());
        verify(runtime, times(2)).activateNow("education.other");
        verifyNoMoreInteractions(runtime);
    }

    @Test
    void shouldRefreshCompleteCatalogueOnceAfterCommitInCapturedSystemScope() {
        PlatformModule module = module();
        when(relations.list(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(new ModuleMetadataRelation()));
        when(runtime.activateNow(module.getAlias())).thenAnswer(invocation -> {
            assertThat(TenantContext.isSystem()).isTrue();
            assertThat(actions.list(Criteria.of())).hasSize(8);
            return null;
        });

        try (TenantContext.Scope ignored = TenantContext.use("tenant-request")) {
            transactions.executeWithoutResult(status -> {
                registrar.register(module);
                verifyNoInteractions(runtime);
                assertThat(TenantContext.currentTenantId()).contains("tenant-request");
            });
            verify(runtime, times(1)).activateNow(module.getAlias());
            verify(runtime, never()).refresh(any());
            assertThat(TenantContext.currentTenantId()).contains("tenant-request");
        }
    }

    @Test
    void shouldNotRefreshOnRollback() {
        PlatformModule module = module();
        transactions.executeWithoutResult(status -> {
            registrar.register(module);
            status.setRollbackOnly();
        });
        verifyNoInteractions(runtime, relations);
    }

    @Test
    void shouldKeepCatalogueBeforeMainMetadataExists() {
        PlatformModule module = module();
        transactions.executeWithoutResult(status -> registrar.register(module));
        assertThat(actions.list(Criteria.of())).hasSize(8);
        verifyNoInteractions(runtime);
    }

    @Test
    void shouldSurfaceConfiguredModuleCompilationFailure() {
        PlatformModule module = module();
        when(relations.list(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(new ModuleMetadataRelation()));
        when(runtime.activateNow(module.getAlias())).thenThrow(new IllegalStateException("invalid definition"));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> registrar.register(module)))
                .hasRootCauseMessage("invalid definition");
    }

    @Test
    void shouldRegisterStartupCatalogueWithoutActivatingBeforeMetadataRestoration() {
        module();
        registrar.run();
        assertThat(actions.list(Criteria.of())).hasSize(8);
        verifyNoInteractions(runtime, relations);
    }

    private PlatformModule module() {
        PlatformModule module = new PlatformModule();
        module.setAlias("education.project");
        module.setApplicationAlias("education");
        module.setTitle("项目任务");
        module.setModuleKind(ModuleKind.DYNAMIC);
        modules.insert(module);
        return module;
    }
}
