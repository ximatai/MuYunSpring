package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedMutationScopeExecutorTest {
    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void shouldKeepResolvedCriteriaOpaqueOutsideTheAbilityPackage() {
        assertThat(Arrays.stream(VerifiedMutationScope.class.getMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("criteriaResult");
    }

    @Test
    void shouldMatchNestedScopesByServiceOperationAndRecordWithoutReResolvingPolicy() {
        ScopeAwareService service = serviceWith("record-1", "record-2");
        VerifiedMutationScope first = verified(service, "record-1");
        VerifiedMutationScope second = verified(service, "record-2");

        VerifiedMutationScopeExecutor.execute(service, PlatformAction.UPDATE, Set.of("record-1"), first, () -> {
            VerifiedMutationScopeExecutor.execute(service, PlatformAction.UPDATE, Set.of("record-2"), second,
                    () -> service.update(update("record-2", "second")));
            return service.update(update("record-1", "first"));
        });

        assertThat(service.scopeResolutions).isZero();
        assertThat(service.select("record-1").getTitle()).isEqualTo("first");
        assertThat(service.select("record-2").getTitle()).isEqualTo("second");
    }

    @Test
    void shouldFailClosedForAMismatchedBoundMutationAndRejectTokenReuse() {
        ScopeAwareService service = serviceWith("record-1", "record-2");
        VerifiedMutationScope scope = verified(service, "record-1");

        assertThatThrownBy(() -> VerifiedMutationScopeExecutor.execute(service, PlatformAction.UPDATE,
                Set.of("record-1"), scope,
                () -> service.update(update("record-2", "forged"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> VerifiedMutationScopeExecutor.execute(service, PlatformAction.UPDATE,
                Set.of("record-1"), scope,
                () -> service.update(update("record-1", "reused"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void shouldRejectExecutorBindingForAnotherServiceOperationOrRecord() {
        ScopeAwareService service = serviceWith("record-1");
        ScopeAwareService other = serviceWith("record-1");

        assertThatThrownBy(() -> VerifiedMutationScopeExecutor.execute(other, PlatformAction.UPDATE,
                Set.of("record-1"), verified(service, "record-1"), () -> 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid");
        assertThatThrownBy(() -> VerifiedMutationScopeExecutor.execute(service, PlatformAction.DELETE,
                Set.of("record-1"), verified(service, "record-1"), () -> 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid");
        assertThatThrownBy(() -> VerifiedMutationScopeExecutor.execute(service, PlatformAction.UPDATE,
                Set.of("record-2"), verified(service, "record-1"), () -> 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid");
    }

    @Test
    void shouldLimitCompanionReadToTheVerifiedServiceOperationAndRecord() {
        ScopeAwareService service = serviceWith("record-1", "record-2");
        ScopeAwareService other = serviceWith("record-1");
        VerifiedMutationScope scope = verified(service, "record-1");

        assertThat(VerifiedMutationScopeExecutor.select(
                service, PlatformAction.UPDATE, "record-1", scope).getId()).isEqualTo("record-1");
        assertThatThrownBy(() -> VerifiedMutationScopeExecutor.select(
                service, PlatformAction.UPDATE, "record-2", scope)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> VerifiedMutationScopeExecutor.select(
                service, PlatformAction.DELETE, "record-1", scope)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> VerifiedMutationScopeExecutor.select(
                other, PlatformAction.UPDATE, "record-1", scope)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldCleanUpAfterFailureAndLeaveOrdinaryCrudScopeResolutionUnchanged() {
        ScopeAwareService service = serviceWith("record-1");
        VerifiedMutationScope scope = verified(service, "record-1");

        assertThatThrownBy(() -> VerifiedMutationScopeExecutor.execute(service, PlatformAction.UPDATE,
                Set.of("record-1"), scope, () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("boom");

        service.update(update("record-1", "ordinary"));
        assertThat(service.scopeResolutions).isEqualTo(1);
        assertThat(service.lastScopeModuleAlias).isEqualTo("child.module");
        assertThat(service.lastScopeActionCode).isEqualTo(PlatformAction.UPDATE.code());
    }

    @Test
    void shouldKeepCrossTenantBypassForTheWholePolymorphicServiceCallAndRestoreIt() {
        ScopeAwareService service = serviceWith("record-1");
        VerifiedMutationScope scope = new VerifiedMutationScope(service, PlatformAction.UPDATE,
                Set.of("record-1"), DataScopeCriteriaResult.crossTenantRestricted(
                Criteria.of().eq("id", "record-1")));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            VerifiedMutationScopeExecutor.execute(service, PlatformAction.UPDATE, Set.of("record-1"), scope, () -> {
                assertThat(TenantContext.tenantFilterBypassed()).isTrue();
                return service.update(update("record-1", "cross-tenant"));
            });
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }

    private static VerifiedMutationScope verified(ScopeAwareService service, String id) {
        return new VerifiedMutationScope(service, PlatformAction.UPDATE, Set.of(id),
                DataScopeCriteriaResult.restricted(Criteria.of().eq("id", id)));
    }

    private static ScopeAwareService serviceWith(String... ids) {
        InMemoryBaseDao<DemoPlainRecord> dao = new InMemoryBaseDao<>();
        for (String id : ids) {
            DemoPlainRecord record = update(id, id);
            dao.insert(record);
        }
        return new ScopeAwareService(dao);
    }

    private static DemoPlainRecord update(String id, String title) {
        DemoPlainRecord record = new DemoPlainRecord(title);
        record.setId(id);
        record.setVersion(0);
        return record;
    }

    private static final class ScopeAwareService extends AbstractAbilityService<DemoPlainRecord>
            implements DataScopeAbility<DemoPlainRecord> {
        private int scopeResolutions;
        private String lastScopeModuleAlias;
        private String lastScopeActionCode;

        private ScopeAwareService(BaseDao<DemoPlainRecord, String> dao) {
            super("child.module", DemoPlainRecord.class, dao);
        }

        @Override
        public DataScopeCriteriaService getDataScopeCriteriaService() {
            return new DataScopeCriteriaService() {
                @Override
                public DataScopeCriteriaResult resolveReadScope(String moduleAlias, String actionCode,
                                                                Criteria criteria,
                                                                Optional<CurrentUser> currentUser) {
                    throw new AssertionError("policy overload is required");
                }

                @Override
                public DataScopeCriteriaResult resolveReadScope(String moduleAlias, ActionExecutionPolicy policy,
                                                                Criteria criteria,
                                                                Optional<CurrentUser> currentUser) {
                    scopeResolutions++;
                    lastScopeModuleAlias = moduleAlias;
                    lastScopeActionCode = policy.actionCode();
                    return DataScopeCriteriaResult.restricted(criteria);
                }
            };
        }
    }
}
