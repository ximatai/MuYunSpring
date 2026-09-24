package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbilityCompositionContractTest {
    @AfterEach
    void reset() {
        TenantContext.clear();
        PlatformAbilityRuntime.resetDataScopeCriteriaService();
    }

    @Test
    void tenantGuardsAndNormalizationMustSurviveOverriddenBusinessHooks() {
        ScopedService service = new ScopedService();
        DemoPlainRecord record = new DemoPlainRecord("  Name  ");
        assertThatThrownBy(() -> service.insert(record)).isInstanceOf(PlatformAccessDeniedException.class);
        try (var ignored = TenantContext.use("tenant-a")) {
            service.insert(record);
            assertThat(record.getTitle()).isEqualTo("Name");
            record.setTitle("  Updated  ");
            assertThat(service.update(record)).isEqualTo(1);
            assertThat(record.getTitle()).isEqualTo("Updated");
        }
        assertThatThrownBy(() -> service.update(record)).isInstanceOf(PlatformAccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(record)).isInstanceOf(PlatformAccessDeniedException.class);
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThat(service.delete(record)).isEqualTo(1);
        }
        assertThatThrownBy(() -> service.restore(record.getId())).isInstanceOf(PlatformAccessDeniedException.class);
        assertThatThrownBy(() -> service.purge(record.getId())).isInstanceOf(PlatformAccessDeniedException.class);
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThat(service.restore(record.getId())).isEqualTo(1);
        }
    }

    @Test
    void purgeChecksMutationContextBeforeCallingBusinessPolicy() {
        var hooks = new java.util.ArrayList<String>();
        ScopedService service = new ScopedService() {
            @Override public void beforeRecycleBinPurge(String id) { hooks.add("recycle"); }
            @Override public void beforeRetainedRecordPurge(String id) { hooks.add("retained"); }
        };
        DemoPlainRecord record = new DemoPlainRecord("Retained");
        try (var ignored = TenantContext.use("tenant-a")) {
            service.insert(record);
            service.delete(record);
        }
        assertThat(service.purge(null)).isZero();
        assertThat(service.purge(" ")).isZero();
        assertThatThrownBy(() -> service.purge(record.getId())).isInstanceOf(PlatformAccessDeniedException.class);
        assertThat(hooks).isEmpty();
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThat(service.purge(record.getId(), record.getVersion())).isEqualTo(1);
        }
        assertThat(hooks).containsExactly("recycle", "retained");
    }

    @Test
    void systemGuardMustSurviveAllBusinessHookOverrides() {
        SystemService service = new SystemService();
        DemoPlainRecord record = new DemoPlainRecord("System");
        assertThatThrownBy(() -> service.insert(record)).isInstanceOf(PlatformAccessDeniedException.class);
        try (var ignored = TenantContext.system("fixture")) {
            service.insert(record);
        }
        assertThatThrownBy(() -> service.update(record)).isInstanceOf(PlatformAccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(record)).isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test
    void recycleBinMustComposeDataScopeForPagesAndRecoverySources() {
        ScopedRecycleService service = new ScopedRecycleService();
        DemoPlainRecord allowed = new DemoPlainRecord("Allowed");
        DemoPlainRecord denied = new DemoPlainRecord("Denied");
        service.insert(allowed);
        service.insert(denied);
        // Model persisted retained facts independently of deletion authorization.
        allowed.setDeleted(true);
        denied.setDeleted(true);
        PlatformAbilityRuntime.configureDataScopeCriteriaService(() -> new AllowAllDataScopeCriteriaService() {
            @Override
            public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy,
                                                            Criteria criteria, Optional<CurrentUser> user) {
                return DataScopeCriteriaResult.unrestricted(criteria.eq("id", allowed.getId()));
            }
        });
        assertThat(service.pageRecycleBin(Criteria.of(), PageRequest.of(1, 20)).getRecords())
                .containsExactly(allowed);
        assertThat(service.canAccessRecycleBinRecord(denied.getId())).isFalse();
        assertThat(service.canAccessRecycleBinSourceRecord(denied.getId())).isFalse();
        allowed.setDeleted(false);
        assertThat(service.canAccessRecycleBinRecord(allowed.getId())).isFalse();
        assertThat(service.canAccessRecycleBinSourceRecord(allowed.getId())).isTrue();
    }

    @Test
    void scopedTraversalMustExcludeForeignBranchesAndMissingRoots() {
        DemoOrganizationService service = new DemoOrganizationService();
        DemoOrganization root = new DemoOrganization("Root", TreeAbility.ROOT_ID);
        root.setScopeKey("a");
        service.insert(root);
        DemoOrganization child = new DemoOrganization("Child", root.getId());
        child.setScopeKey("a");
        service.insert(child);
        DemoOrganization foreign = new DemoOrganization("Foreign", child.getId());
        foreign.setScopeKey("b");
        service.insert(foreign);
        assertThat(service.selfAndDescendantIds(Criteria.of().eq("scopeKey", "a"), root.getId()))
                .containsExactly(root.getId(), child.getId());
        assertThat(service.selfAndDescendantIds(Criteria.of().eq("scopeKey", "b"), root.getId())).isEmpty();
        assertThat(service.selfAndDescendantIds("missing")).isEmpty();
    }

    @Test
    void virtualRootTraversalMustApplyScopeToEveryBranch() {
        DemoOrganizationService service = new DemoOrganizationService();
        DemoOrganization top = new DemoOrganization("Top", TreeAbility.ROOT_ID);
        top.setScopeKey("a");
        service.insert(top);
        DemoOrganization leaf = new DemoOrganization("Leaf", top.getId());
        leaf.setScopeKey("a");
        service.insert(leaf);
        DemoOrganization foreign = new DemoOrganization("Foreign", TreeAbility.ROOT_ID);
        foreign.setScopeKey("b");
        service.insert(foreign);
        assertThat(service.descendantIds(TreeAbility.ROOT_ID))
                .containsExactly(top.getId(), leaf.getId(), foreign.getId());
        assertThat(service.selfAndDescendantIds(TreeAbility.ROOT_ID))
                .containsExactly(TreeAbility.ROOT_ID, top.getId(), leaf.getId(), foreign.getId());
        Criteria scope = Criteria.of().eq("scopeKey", "a");
        assertThat(service.descendantIds(scope, TreeAbility.ROOT_ID)).containsExactly(top.getId(), leaf.getId());
        assertThat(service.selfAndDescendantIds(scope, TreeAbility.ROOT_ID))
                .containsExactly(TreeAbility.ROOT_ID, top.getId(), leaf.getId());
        assertThat(service.descendantIds("missing")).isEmpty();
    }

    @Test
    void recycleBinMustApplyTenantCriteriaAfterResolvingCrossTenantScope() {
        ScopedRecycleService service = new ScopedRecycleService();
        DemoPlainRecord local = new DemoPlainRecord("Local");
        DemoPlainRecord foreign = new DemoPlainRecord("Foreign");
        try (var ignored = TenantContext.use("tenant-a")) {
            service.insert(local);
        }
        try (var ignored = TenantContext.use("tenant-b")) {
            service.insert(foreign);
        }
        local.setDeleted(true);
        foreign.setDeleted(true);
        PlatformAbilityRuntime.configureDataScopeCriteriaService(() -> new AllowAllDataScopeCriteriaService() {
            @Override
            public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy,
                                                            Criteria criteria, Optional<CurrentUser> user) {
                return DataScopeCriteriaResult.crossTenantRestricted(criteria.eq("id", foreign.getId()));
            }
        });
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThat(service.pageRecycleBin(Criteria.of(), PageRequest.of(1, 20)).getRecords())
                    .containsExactly(foreign);
            assertThat(service.canAccessRecycleBinRecord(foreign.getId())).isTrue();
            assertThat(service.canAccessRecycleBinSourceRecord(foreign.getId())).isTrue();
            assertThat(service.canAccessRecycleBinRecord(local.getId())).isFalse();
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
            PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
            assertThat(service.pageRecycleBin(Criteria.of(), PageRequest.of(1, 20)).getRecords())
                    .containsExactly(local);
            assertThat(service.canAccessRecycleBinSourceRecord(foreign.getId())).isFalse();
        }
    }

    @Test
    void softDeletionMustRejectDeniedRecordsBeforeBusinessHooks() {
        ScopedRecycleService service = new ScopedRecycleService();
        DemoPlainRecord record = new DemoPlainRecord("Denied");
        service.insert(record);
        PlatformAbilityRuntime.configureDataScopeCriteriaService(() -> new AllowAllDataScopeCriteriaService() {
            @Override
            public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy,
                                                            Criteria criteria, Optional<CurrentUser> user) {
                return DataScopeCriteriaResult.restricted(criteria.eq("id", "not-visible"));
            }
        });
        assertThatThrownBy(() -> service.delete(record.getId()))
                .isInstanceOf(PlatformAccessDeniedException.class);
        assertThat(service.deleteHooks).isZero();
        assertThat(service.selectActiveRaw(record.getId())).isNotNull();
    }

    @Test
    void softDeleteModelMustHonorCrossTenantUpdateAndDeletePermissions() {
        ScopedRecycleService service = new ScopedRecycleService();
        DemoPlainRecord record = new DemoPlainRecord("Foreign");
        try (var ignored = TenantContext.use("tenant-b")) {
            service.insert(record);
        }
        PlatformAbilityRuntime.configureDataScopeCriteriaService(() -> new AllowAllDataScopeCriteriaService() {
            @Override
            public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy,
                                                            Criteria criteria, Optional<CurrentUser> user) {
                return DataScopeCriteriaResult.crossTenantRestricted(criteria.eq("id", record.getId()));
            }
        });
        try (var ignored = TenantContext.use("tenant-a")) {
            DemoPlainRecord draft = new DemoPlainRecord("Updated");
            draft.setId(record.getId());
            draft.setVersion(record.getVersion());
            assertThat(service.update(draft)).isEqualTo(1);
            assertThat(draft.getTenantId()).isEqualTo("tenant-b");
            assertThat(service.delete(draft)).isEqualTo(1);
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
        try (var ignored = TenantContext.use("tenant-b")) {
            assertThat(service.selectActiveRaw(record.getId())).isNull();
            assertThat(service.selectIgnoreSoftDelete(record.getId()).getTitle()).isEqualTo("Updated");
        }
    }

    @Test
    void classProxyMustRetainModelDaoAndModuleIdentity() {
        ScopedService target = new ScopedService();
        org.springframework.aop.framework.ProxyFactory factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
        ScopedService proxy = (ScopedService) factory.getProxy();
        assertThat(proxy.modelClass()).isEqualTo(DemoPlainRecord.class);
        assertThat(proxy.getDao()).isSameAs(target.getDao());
        assertThat(proxy.getModuleAlias()).isEqualTo("test.scoped");
    }

    @Test
    void missingPermissionRuntimeMustNotSilentlyAllowAccess() {
        ScopedRecycleService service = new ScopedRecycleService();
        assertThatThrownBy(() -> service.listForAction(
                net.ximatai.muyun.spring.common.platform.PlatformAction.QUERY, Criteria.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DataScopeCriteriaService");
    }

    private static class ScopedService extends TenantActiveScopedService<DemoPlainRecord>
            implements RecycleBinAbility<DemoPlainRecord> {
        ScopedService() { super("test.scoped", DemoPlainRecord.class, new InMemoryBaseDao<>(), id -> {}); }
        @Override public void normalizeBeforeMutation(DemoPlainRecord record) { record.setTitle(record.getTitle().trim()); }
        @Override public void beforePrepareInsert(DemoPlainRecord record) {}
        @Override public void beforeUpdate(DemoPlainRecord record) {}
        @Override public void beforeDelete(String id) {}
        @Override public boolean isRecycleBinPurgeEnabled() { return true; }
        @Override public void beforeRecycleBinPurge(String id) {}
    }

    private static class SystemService extends AbstractAbilityService<DemoPlainRecord>
            implements SystemManagedAbility<DemoPlainRecord> {
        SystemService() { super("test.system", DemoPlainRecord.class, new InMemoryBaseDao<>()); }
        @Override public void beforePrepareInsert(DemoPlainRecord record) {}
        @Override public void beforeUpdate(DemoPlainRecord record) {}
        @Override public void beforeDelete(String id) {}
    }

    private static class ScopedRecycleService extends AbstractAbilityService<DemoPlainRecord>
            implements RecycleBinAbility<DemoPlainRecord>, DataScopeAbility<DemoPlainRecord> {
        int deleteHooks;
        ScopedRecycleService() { super("test.recycle", DemoPlainRecord.class, new InMemoryBaseDao<>()); }
        @Override public void beforeDelete(String id) { deleteHooks++; }
    }
}
