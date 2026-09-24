package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedMutationAbilityTest {
    @Test
    void tenantVerificationShouldDelegateThroughSpringClassProxy() {
        java.util.List<String> verified = new java.util.ArrayList<>();
        ProxyFactory factory = new ProxyFactory(new ProxyableTenantScopedService(tenantId -> {
            verified.add(tenantId);
            if ("disabled".equals(tenantId)) throw new IllegalStateException("tenant is disabled");
        }));
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        TenantActiveScopedAbility<?> proxy = (TenantActiveScopedAbility<?>) factory.getProxy();

        proxy.verifyActiveTenant("active");
        assertThatThrownBy(() -> proxy.verifyActiveTenant("disabled"))
                .isInstanceOf(IllegalStateException.class).hasMessage("tenant is disabled");
        assertThat(verified).containsExactly("active", "disabled");
    }

    static class ProxyableTenantScopedService extends TenantActiveScopedService<DemoPlainRecord> {
        ProxyableTenantScopedService(net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier verifier) {
            super("demo.tenantProxy", DemoPlainRecord.class, new InMemoryBaseDao<>(), verifier);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CacheRegistry.clearAll();
    }

    @Test
    void systemManagedAbilityShouldRequireSystemContextForMutation() {
        SystemManagedDemoService service = new SystemManagedDemoService();

        assertThatThrownBy(() -> service.insert(new DemoEnabledRecord("Tenant")))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> {
                    assertThat(((PlatformException) exception).code()).isEqualTo(PlatformErrorCodes.ACCESS_DENIED);
                    assertThat(((PlatformException) exception).httpStatus()).isEqualTo(403);
                })
                .hasMessageContaining("system context");

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            String id = service.insert(new DemoEnabledRecord("System"));
            assertThat(service.select(id).getTitle()).isEqualTo("System");
        }
    }

    @Test
    void systemManagedAbilityShouldRequireSystemContextForUpdateAndDelete() {
        SystemManagedDemoService service = new SystemManagedDemoService();
        String id;
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            id = service.insert(new DemoEnabledRecord("System"));
        }

        DemoEnabledRecord update = new DemoEnabledRecord("Updated");
        update.setId(id);
        assertThatThrownBy(() -> service.update(update))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("system context");
        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("system context");
    }

    @Test
    void systemStandardBusinessServiceShouldKeepSystemContextAndSharedHooks() {
        SystemStandardDemoService service = new SystemStandardDemoService();

        assertThatThrownBy(() -> service.insert(new DemoEnabledRecord("Tenant")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("system context");

        String id;
        try (TenantContext.Scope ignored = TenantContext.system("test system standard context")) {
            DemoEnabledRecord record = new DemoEnabledRecord("  System  ");
            id = service.insert(record);
            assertThat(record.getTitle()).isEqualTo("System");
            assertThat(service.saveChecks).isEqualTo(1);
        }

        DemoEnabledRecord update = new DemoEnabledRecord("  Updated  ");
        update.setId(id);
        update.setVersion(0);
        assertThatThrownBy(() -> service.update(update))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("system context");

        try (TenantContext.Scope ignored = TenantContext.system("test system standard context")) {
            DemoEnabledRecord systemUpdate = new DemoEnabledRecord("  Updated  ");
            systemUpdate.setId(id);
            systemUpdate.setVersion(0);
            service.update(systemUpdate);
            assertThat(systemUpdate.getTitle()).isEqualTo("Updated");
            assertThat(service.saveChecks).isEqualTo(2);
        }
    }

    @Test
    void tenantActiveScopedAbilityShouldRequireTenantContextAndVerifyActiveTenant() {
        TenantScopedDemoService service = new TenantScopedDemoService();

        assertThatThrownBy(() -> service.insert(new DemoPlainRecord("No tenant")))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> {
                    assertThat(((PlatformException) exception).code()).isEqualTo(PlatformErrorCodes.ACCESS_DENIED);
                    assertThat(((PlatformException) exception).httpStatus()).isEqualTo(403);
                })
                .hasMessageContaining("tenant context");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String id = service.insert(new DemoPlainRecord("Tenant"));
            assertThat(service.verifiedTenantId()).isEqualTo("tenant-a");
            assertThat(service.select(id).getTenantId()).isEqualTo("tenant-a");
        }
    }

    @Test
    void tenantActiveScopedAbilityShouldRequireTenantContextForUpdateAndDelete() {
        TenantScopedDemoService service = new TenantScopedDemoService();
        String id;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            id = service.insert(new DemoPlainRecord("Tenant"));
        }

        DemoPlainRecord update = new DemoPlainRecord("Updated");
        update.setId(id);
        assertThatThrownBy(() -> service.update(update))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("tenant context");
        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("tenant context");
    }

    @Test
    void globalScopedAbilityShouldKeepSoftDeleteButIgnoreTenantScope() {
        SystemManagedDemoService service = new SystemManagedDemoService();
        String id;
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            id = service.insert(new DemoEnabledRecord("Global"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.select(id)).isNotNull();
        }

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            service.delete(id);
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.select(id)).isNull();
            assertThat(service.selectIgnoreSoftDelete(id)).isNotNull();
        }
    }

    @Test
    void enableAbilityShouldRequireEnabledRecord() {
        SystemManagedDemoService service = new SystemManagedDemoService();
        String id;
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            id = service.insert(new DemoEnabledRecord("Enabled"));
            service.disable(id);
        }

        assertThatThrownBy(() -> service.requireEnabled(id, "Record is disabled"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void globalScopeMustNotChooseSoftDeletionAndMustWorkWithColdAndWarmCaches() {
        GlobalCachedService service = new GlobalCachedService();
        DemoPlainRecord record = new DemoPlainRecord("Global");
        try (var ignored = TenantContext.system("global fixture")) {
            service.insert(record);
        }
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThat(service.select(record.getId()).getTitle()).isEqualTo("Global");
            assertThat(service.selectAllWithCache()).hasSize(1);
        }
        try (var ignored = TenantContext.use("tenant-b")) {
            assertThat(service.select(record.getId()).getTitle()).isEqualTo("Global");
            assertThat(service.selectAllWithCache()).hasSize(1);
        }
        try (var ignored = TenantContext.system("global deletion")) {
            assertThat(service.delete(record.getId())).isEqualTo(1);
            assertThat(service.getDao().findById(record.getId())).isNull();
            assertThat(service.selectAllWithCache()).isEmpty();
        }
    }

    @Test
    void globalRecycleBinMustShareScopeWithActiveAndRetainedReads() {
        GlobalRecycleService service = new GlobalRecycleService();
        DemoPlainRecord record = new DemoPlainRecord("Global");
        try (var ignored = TenantContext.system("global fixture")) {
            service.insert(record);
            service.delete(record.getId());
        }
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThat(service.select(record.getId())).isNull();
            assertThat(service.selectIgnoreSoftDelete(record.getId())).isNotNull();
            assertThat(service.pageRecycleBin(Criteria.of(), PageRequest.of(1, 20)).getRecords())
                    .containsExactly(record);
            assertThat(service.pageRecycleBin(Criteria.of().eq("title", "Other"), PageRequest.of(1, 20)).getRecords())
                    .isEmpty();
            assertThat(service.canAccessRecycleBinRecord(record.getId())).isTrue();
            assertThat(service.canAccessRecycleBinSourceRecord(record.getId())).isTrue();
            assertThat(service.restore(record.getId())).isEqualTo(1);
            assertThat(service.select(record.getId())).isNotNull();
        }
    }

    private static final class GlobalCachedService extends StandardBusinessService<DemoPlainRecord>
            implements GlobalScopedAbility<DemoPlainRecord>, CacheAbility<DemoPlainRecord> {
        GlobalCachedService() { super("demo.globalCached", DemoPlainRecord.class, new InMemoryBaseDao<>()); }
    }

    private static final class GlobalRecycleService extends StandardBusinessService<DemoPlainRecord>
            implements GlobalScopedAbility<DemoPlainRecord>, RecycleBinAbility<DemoPlainRecord> {
        GlobalRecycleService() { super("demo.globalRecycle", DemoPlainRecord.class, new InMemoryBaseDao<>()); }
    }

    private static final class SystemManagedDemoService extends AbstractAbilityService<DemoEnabledRecord> implements
            SystemManagedAbility<DemoEnabledRecord>,
            GlobalScopedAbility<DemoEnabledRecord>,
            SoftDeleteAbility<DemoEnabledRecord>,
            EnableAbility<DemoEnabledRecord> {

        private SystemManagedDemoService() {
            super("demo.systemManaged", DemoEnabledRecord.class, new InMemoryBaseDao<>());
        }
    }

    private static final class SystemStandardDemoService extends SystemStandardBusinessService<DemoEnabledRecord> implements
            GlobalScopedAbility<DemoEnabledRecord>,
            EnableAbility<DemoEnabledRecord> {
        private int saveChecks;

        private SystemStandardDemoService() {
            super("demo.systemStandard", DemoEnabledRecord.class, new InMemoryBaseDao<>());
        }

        @Override
        public void normalizeBeforeMutation(DemoEnabledRecord record) {
            record.setTitle(record.getTitle().trim());
        }

        @Override
        protected void validateBeforeSave(DemoEnabledRecord entity) {
            saveChecks++;
        }
    }

    private static final class TenantScopedDemoService extends TenantActiveScopedService<DemoPlainRecord> {
        private final CapturingTenantVerifier tenantVerifier;

        private TenantScopedDemoService() {
            this(new CapturingTenantVerifier());
        }

        private TenantScopedDemoService(CapturingTenantVerifier tenantVerifier) {
            super("demo.tenantScoped", DemoPlainRecord.class, new InMemoryBaseDao<>(), tenantVerifier);
            this.tenantVerifier = tenantVerifier;
        }

        private String verifiedTenantId() {
            return tenantVerifier.tenantId;
        }
    }

    private static final class CapturingTenantVerifier implements net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier {
        private String tenantId;

        @Override
        public void verifyActiveTenant(String tenantId) {
            this.tenantId = tenantId;
        }
    }
}
