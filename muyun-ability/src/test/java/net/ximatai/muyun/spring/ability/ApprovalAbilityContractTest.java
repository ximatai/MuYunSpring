package net.ximatai.muyun.spring.ability;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.standard.StandardApprovalEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class ApprovalAbilityContractTest {
    private static final ActionExecutionPolicy POLICY = PlatformAction.UPDATE.executionPolicy();
    private static final ApprovalState PROCESSING = new ApprovalState("instance", "processing", "submitter", Instant.EPOCH, null);

    @AfterEach void reset() {
        TenantContext.clear();
        CurrentUserContext.clear();
        CacheRegistry.clearAll();
        PlatformAbilityRuntime.resetDataScopeCriteriaService();
    }

    @Test void ordinaryCrudCannotForgeSummaryAndCommandKeepsActorVersionCacheAndHooks() {
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        Service service = new Service();
        try (var tenant = TenantContext.use("tenant-a");
             var actor = CurrentUserContext.use(CurrentUser.tenantUser("actor", "Actor", "tenant-a"))) {
            Record incoming = new Record(); incoming.setTitle("Business"); incoming.setApprovalStatus("approved");
            String id = service.insert(incoming);
            assertThat(service.select(id).getApprovalStatus()).isNull();
            int version = incoming.getVersion();
            service.writeApprovalState(id, POLICY, PROCESSING);
            Record saved = service.select(id);
            assertThat(saved.getApprovalInstanceId()).isEqualTo("instance");
            assertThat(saved.getApprovalStatus()).isEqualTo("processing");
            assertThat(saved.getTitle()).isEqualTo("Business");
            assertThat(saved.getVersion()).isEqualTo(version + 1);
            assertThat(saved.getUpdatedBy()).isEqualTo("actor");
            assertThat(service.updated).isEqualTo(1);
            saved.setApprovalStatus("approved"); saved.setApprovalInstanceId("forged"); saved.setTitle("Changed");
            assertThatThrownBy(() -> service.update(saved)).hasMessageContaining("不可直接修改");
            service.writeApprovalBusiness(saved);
            assertThat(service.select(id).getApprovalStatus()).isEqualTo("processing");
            assertThat(service.select(id).getTitle()).isEqualTo("Changed");
            service.writeApprovalState(id, POLICY, ApprovalState.empty());
            assertThat(service.select(id).getApprovalInstanceId()).isNull();
            assertThat(ActionExecutionContextHolder.current()).isEmpty();
        }
    }

    @Test void approvedBusinessRequiresTrustedCommandAndFailedCommandDoesNotLeakAuthority() {
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        Service service = new Service();
        try (var tenant = TenantContext.use("a")) {
            String id = service.insert(new Record());
            service.writeApprovalState(id, POLICY, new ApprovalState("instance", "approved", "submitter", Instant.EPOCH, Instant.EPOCH));
            Record draft = service.copyForApprovalMutation(service.select(id)); draft.setTitle("delivery");
            service.reject = true;
            assertThatThrownBy(() -> service.writeApprovalBusiness(draft)).hasMessage("rejected");
            service.reject = false;
            assertThatThrownBy(() -> service.update(draft)).hasMessageContaining("不可直接修改");
            Record delivery = service.copyForApprovalMutation(service.select(id)); delivery.setTitle("delivery");
            service.writeApprovalBusiness(delivery);
            assertThat(service.select(id).getTitle()).isEqualTo("delivery");
            assertThat(service.select(id).getApprovalStatus()).isEqualTo("approved");
            service.writeApprovalState(id, POLICY, new ApprovalState("instance", "rejected", "submitter", Instant.EPOCH, null));
            var correction = service.copyForApprovalMutation(service.select(id)); correction.setTitle("correction");
            service.update(correction);
            assertThat(service.select(id).getTitle()).isEqualTo("correction");
        }
    }

    @Test void commandCannotCrossTenantOrBypassDataScope() {
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        Service service = new Service();
        String id;
        try (var tenant = TenantContext.use("a")) { id = service.insert(new Record()); }
        try (var tenant = TenantContext.use("b")) {
            assertThatThrownBy(() -> service.writeApprovalState(id, POLICY, PROCESSING))
                    .hasMessageContaining("record data permission denied");
        }
        PlatformAbilityRuntime.configureDataScopeCriteriaService(() -> new AllowAllDataScopeCriteriaService() {
            @Override public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy,
                    net.ximatai.muyun.database.core.orm.Criteria criteria,
                    java.util.Optional<CurrentUser> user) {
                return DataScopeCriteriaResult.crossTenantUnrestricted(criteria);
            }
        });
        try (var tenant = TenantContext.use("b")) {
            assertThat(service.writeApprovalState(id, POLICY, PROCESSING)).isZero();
        }
        PlatformAbilityRuntime.configureDataScopeCriteriaService(() -> new AllowAllDataScopeCriteriaService() {
            @Override public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy,
                    net.ximatai.muyun.database.core.orm.Criteria criteria,
                    java.util.Optional<CurrentUser> user) {
                return DataScopeCriteriaResult.restricted(criteria.eq("id", "denied"));
            }
        });
        try (var tenant = TenantContext.use("a")) {
            assertThatThrownBy(() -> service.writeApprovalState(id, POLICY, PROCESSING))
                    .hasMessageContaining("record data permission denied");
            assertThat(service.selectActiveRaw(id).getApprovalStatus()).isNull();
        }
    }

    @Test void failedHookDoesNotLeakPermissionAndStaleUpdateIsRejected() {
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        Service service = new Service();
        try (var tenant = TenantContext.use("a")) {
            String id = service.insert(new Record());
            Record stale = service.select(id);
            service.reject = true;
            assertThatThrownBy(() -> service.writeApprovalState(id, POLICY, PROCESSING)).hasMessage("rejected");
            service.reject = false;
            Record forged = service.select(id); forged.setApprovalStatus("approved");
            service.update(forged);
            assertThat(service.select(id).getApprovalStatus()).isNull();
            assertThatThrownBy(() -> service.update(stale)).isInstanceOf(OptimisticLockException.class);
            assertThat(ActionExecutionContextHolder.current()).isEmpty();
        }
    }

    @Test void hookCannotRedirectAnApprovalCommandToAnotherRecordOrTenant() {
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        Service service = new Service();
        try (var tenant = TenantContext.use("a")) {
            String id = service.insert(new Record());
            service.redirectId = "other-record";
            assertThatThrownBy(() -> service.writeApprovalState(id, POLICY, PROCESSING)).hasMessageContaining("记录身份或租户");
            service.redirectId = null;
            service.redirectTenant = "other-tenant";
            assertThatThrownBy(() -> service.writeApprovalState(id, POLICY, PROCESSING)).hasMessageContaining("记录身份或租户");
            service.redirectTenant = null;
            service.writeApprovalState(id, POLICY, PROCESSING);
            service.redirectId = "another";
            assertThatThrownBy(() -> service.writeApprovalBusiness(service.copyForApprovalMutation(service.select(id))))
                    .hasMessageContaining("记录身份或租户");
        }
    }

    @Test void commandRestoresEncryptedBusinessFieldsBeforeStandardPersistence() {
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        ProtectedService service = new ProtectedService();
        try (var tenant = TenantContext.use("a")) {
            ProtectedRecord record = new ProtectedRecord(); record.setSecret("plain");
            String id = service.insert(record);
            assertThat(service.selectActiveRaw(id).getSecret()).isEqualTo("enc:plain");
            service.writeApprovalState(id, POLICY, PROCESSING);
            assertThat(service.selectActiveRaw(id).getSecret()).isEqualTo("enc:plain");
            assertThat(service.select(id).getSecret()).isEqualTo("plain");
        }
    }

    @Getter @Setter static class ProtectedRecord extends StandardApprovalEntity {
        @net.ximatai.muyun.spring.common.security.EncryptedField private String secret;
    }
    static class ProtectedService extends AbstractAbilityService<ProtectedRecord>
            implements ApprovalAbility<ProtectedRecord>, net.ximatai.muyun.spring.ability.security.FieldProtectionAbility<ProtectedRecord> {
        ProtectedService() { super("test.protected_approval", ProtectedRecord.class, new CopyingDao()); }
        @Override public net.ximatai.muyun.spring.ability.security.FieldCryptoProvider fieldCryptoProvider() {
            return new net.ximatai.muyun.spring.ability.security.FieldCryptoProvider() {
                public String encrypt(String field, Object value) { return "enc:" + value; }
                public Object decrypt(String field, String value) { return value.substring(4); }
            };
        }
    }
    static class CopyingDao extends InMemoryBaseDao<ProtectedRecord> {
        @Override public String insert(ProtectedRecord record) { return super.insert(EntityRecordCopies.forFieldMutation(record)); }
        @Override public int updateById(ProtectedRecord record) { return super.updateById(EntityRecordCopies.forFieldMutation(record)); }
        @Override public java.util.List<ProtectedRecord> query(net.ximatai.muyun.database.core.orm.Criteria criteria,
                net.ximatai.muyun.database.core.orm.PageRequest page, net.ximatai.muyun.database.core.orm.Sort... sort) {
            return super.query(criteria, page, sort).stream().map(record -> EntityRecordCopies.<ProtectedRecord>forFieldMutation(record)).toList();
        }
    }

    @Getter @Setter static class Record extends StandardApprovalEntity { private String title; }
    static class Service extends AbstractAbilityService<Record>
            implements ApprovalAbility<Record>, DataScopeAbility<Record>, CacheAbility<Record> {
        int updated; boolean reject; String redirectId; String redirectTenant;
        Service() { super("test.approval", Record.class, new InMemoryBaseDao<>()); }
        @Override public void beforeUpdate(Record record, Record existing) {
            if (reject) throw new IllegalArgumentException("rejected");
            if (redirectId != null) record.setId(redirectId);
            if (redirectTenant != null) record.setTenantId(redirectTenant);
        }
        @Override public void afterUpdate(Record record, int count) { updated++; }
    }
}
