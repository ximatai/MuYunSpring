package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;

class RelatedRecordDeletionTest {
    private static final ActionExecutionPolicy POLICY = new ActionExecutionPolicy("manageAccount", PlatformActionLevel.RECORD,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, null);
    private final Owners owners = new Owners();
    private final Bindings bindings = new Bindings();
    private final Targets targets = new Targets();
    private final RelatedRecordDeletion<Row, Link, Row> deletion = new RelatedRecordDeletion<>(
            owners, bindings, link -> link.ownerId, targets, link -> link.targetId);
    private final CurrentUser actor = CurrentUser.tenantUser("operator", "Operator", "tenant-a");
    private boolean authorized = true;
    private ActionExecutionContext authorizedContext;
    private final ActionExecutionPolicyService authorization = context -> {
        if (!authorized) throw new PlatformAccessDeniedException("denied source action");
        authorizedContext = context;
    };

    @BeforeEach void prepare() {
        TenantContext.setTenantId("tenant-a");
        CurrentUserContext.use(actor);
        // Unit tests exercise authorization and lifecycle. Database rollback is covered by repository IT.
        TransactionSynchronizationManager.setActualTransactionActive(true);
        owners.getDao().insert(row("owner"));
        targets.getDao().insert(row("target"));
        Link link = new Link();
        link.setId("binding"); link.setTenantId("tenant-a"); link.setVersion(0);
        link.ownerId = "owner"; link.targetId = "target";
        bindings.getDao().insert(link);
    }

    @AfterEach void clear() {
        TenantContext.clear();
        CurrentUserContext.clear();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test void preservesActorAndNormalDeleteOverrideWhileUsingSourceAuthorization() {
        var result = deletion.delete("owner", () -> "binding", POLICY, authorization);
        assertThat(result.targetDeleted()).isEqualTo(1);
        assertThat(authorizedContext.moduleAlias()).isEqualTo("test.owner");
        assertThat(authorizedContext.actionCode()).isEqualTo("manageAccount");
        assertThat(authorizedContext.recordIds()).containsExactly("owner");
        assertThat(targets.deletedBy).isEqualTo(actor);
        assertThat(targets.overrides).isEqualTo(1);
        assertThat(CurrentUserContext.currentUser()).contains(actor);
        assertThat(bindings.getDao().findById("binding")).isNull();
        assertThat(targets.getDao().findById("target")).isNull();
        assertThatThrownBy(() -> targets.delete("target"))
                .isInstanceOf(PlatformAccessDeniedException.class).hasMessage("target scope denied");
    }

    @Test void refusesMissingSourceActionPermissionAndInvisibleSourceBeforeWrites() {
        authorized = false;
        assertThatThrownBy(() -> deletion.delete("owner", () -> "binding", POLICY, authorization))
                .isInstanceOf(PlatformAccessDeniedException.class);
        authorized = true;
        owners.visible = false;
        assertThatThrownBy(() -> deletion.delete("owner", () -> "binding", POLICY, authorization))
                .isInstanceOf(PlatformAccessDeniedException.class);
        assertUnchanged();
    }

    @Test void rejectsBindingsOfAnotherOwnerAndCrossTenantTargets() {
        bindings.getDao().findById("binding").ownerId = "other";
        assertThatThrownBy(() -> deletion.delete("owner", () -> "binding", POLICY, authorization))
                .isInstanceOf(PlatformAccessDeniedException.class);
        bindings.getDao().findById("binding").ownerId = "owner";
        targets.getDao().findById("target").setTenantId("tenant-b");
        assertThatThrownBy(() -> deletion.delete("owner", () -> "binding", POLICY, authorization))
                .isInstanceOf(PlatformAccessDeniedException.class);
        assertUnchanged();
    }

    @Test void bindingVersionChangeCannotAuthorizeDeletionOfTheOldTarget() {
        bindings.beforeDelete = () -> {
            Link current = bindings.getDao().findById("binding");
            current.setVersion(1);
            bindings.getDao().updateById(current);
        };
        assertThatThrownBy(() -> deletion.delete("owner", () -> "binding", POLICY, authorization))
                .isInstanceOf(OptimisticLockException.class);
        assertUnchanged();
    }

    @Test void refusesActorReplacementInsideDownstreamOverride() {
        targets.beforeDelete = () -> CurrentUserContext.use(CurrentUser.systemUser("substitute", "Substitute"));
        assertThatThrownBy(() -> deletion.delete("owner", () -> "binding", POLICY, authorization))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match");
        assertThat(targets.getDao().findById("target")).isNotNull();
        assertThat(CurrentUserContext.currentUser()).contains(actor);
    }

    @Test void missingTargetAllowsDanglingBindingCleanupButStillRequiresSourceAuthorization() {
        targets.getDao().deleteById("target");
        var result = deletion.delete("owner", () -> "binding", POLICY, authorization);
        assertThat(result.targetId()).isEqualTo("target");
        assertThat(result.targetDeleted()).isZero();
        assertThat(bindings.getDao().findById("binding")).isNull();
        authorized = false;
        assertThatThrownBy(() -> deletion.delete("owner", () -> null, POLICY, authorization))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test void bindingLookupRunsInTheAuthorizedOwnersTenantAndRestoresTheCallerScope() {
        try (var ignored = TenantContext.system("source authorization fixture")) {
            deletion.delete("owner", () -> {
                assertThat(TenantContext.currentTenantId()).contains("tenant-a");
                assertThat(TenantContext.isSystem()).isFalse();
                return "binding";
            }, POLICY, authorization);
            assertThat(TenantContext.isSystem()).isTrue();
            assertThat(CurrentUserContext.currentUser()).contains(actor);
        }
    }

    @Test void lookupCannotElevateTenantContextBeforeBindingDeletion() {
        assertThatThrownBy(() -> deletion.delete("owner", () -> {
            TenantContext.bypassTenantFilter("unexpected elevation");
            return "binding";
        }, POLICY, authorization)).isInstanceOf(PlatformAccessDeniedException.class);
        assertThat(TenantContext.currentTenantId()).contains("tenant-a");
        assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        assertUnchanged();
    }

    @Test void requiresTransactionBeforeAnyDeletion() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> deletion.delete("owner", () -> "binding", POLICY, authorization))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertThat(bindings.getDao().findById("binding")).isNotNull();
        assertThat(targets.getDao().findById("target")).isNotNull();
        assertThat(targets.overrides).isZero();
    }

    private Row row(String id) { Row row = new Row(); row.setId(id); row.setTenantId("tenant-a"); row.setVersion(0); return row; }
    static class Row extends StandardEntity {}
    static class Link extends StandardEntity { String ownerId; String targetId; }
    static class Owners extends StandardBusinessService<Row> implements DataScopeAbility<Row> {
        boolean visible = true;
        Owners() { super("test.owner", Row.class, new InMemoryBaseDao<>()); }
        @Override public DataScopeCriteriaService getDataScopeCriteriaService() { return new AllowAllDataScopeCriteriaService(); }
        @Override public long count(Criteria criteria) { return visible ? super.count(criteria) : 0; }
    }
    static class Bindings extends StandardBusinessService<Link> {
        Runnable beforeDelete = () -> {};
        Bindings() { super("test.binding", Link.class, new InMemoryBaseDao<>()); }
        @Override public void beforeDelete(String id) { beforeDelete.run(); }
    }
    static class Targets extends StandardBusinessService<Row> implements DataScopeAbility<Row> {
        int overrides;
        CurrentUser deletedBy;
        Runnable beforeDelete = () -> {};
        Targets() { super("test.target", Row.class, new InMemoryBaseDao<>()); }
        @Override public DataScopeCriteriaService getDataScopeCriteriaService() {
            throw new PlatformAccessDeniedException("target scope denied");
        }
        @Override public int delete(String id, Integer version) {
            beforeDelete.run(); overrides++;
            return super.delete(id, version);
        }
        @Override public void afterDelete(String id, Row row, int deleted) {
            deletedBy = CurrentUserContext.currentUser().orElseThrow();
        }
    }
}
