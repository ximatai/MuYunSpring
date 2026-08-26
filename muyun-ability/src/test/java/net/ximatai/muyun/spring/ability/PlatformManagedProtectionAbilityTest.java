package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.capability.PlatformManagedCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformManagedProtectionAbilityTest {

    @Test
    void ordinaryMutationShouldNotCreatePlatformManagedRecord() {
        ManagedRecordService service = new ManagedRecordService();
        ManagedRecord record = managedRecord("managed", "Managed");

        assertThatThrownBy(() -> service.insert(record))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
    }

    @Test
    void platformManagedMutationShouldCreatePlatformManagedRecord() {
        ManagedRecordService service = new ManagedRecordService();
        ManagedRecord record = managedRecord("managed", "Managed");

        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(record));

        assertThat(service.select(record.getId()).getSystemManaged()).isTrue();
    }

    @Test
    void ordinaryMutationShouldNotDeletePlatformManagedRecord() {
        ManagedRecordService service = new ManagedRecordService();
        ManagedRecord record = managedRecord("managed", "Managed");
        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(record));

        assertThatThrownBy(() -> service.delete(record.getId()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
    }

    @Test
    void ordinaryMutationShouldNotUpdateProtectedFieldOnPlatformManagedRecord() {
        ManagedRecordService service = new ManagedRecordService();
        ManagedRecord record = managedRecord("managed", "Managed");
        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(record));

        ManagedRecord update = new ManagedRecord();
        update.setId(record.getId());
        update.setVersion(record.getVersion());
        update.setCode("changed");

        assertThatThrownBy(() -> service.update(update))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
        assertThat(service.select(record.getId()).getCode()).isEqualTo("managed");
    }

    @Test
    void ordinaryMutationShouldLightweightUpdateAllowedFieldsOnPlatformManagedRecord() {
        ManagedRecordService service = new ManagedRecordService();
        ManagedRecord record = managedRecord("managed", "Managed");
        record.setTenantId("tenant-a");
        record.setSortOrder(10);
        record.setEnabled(Boolean.TRUE);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(record));

        ManagedRecord update = new ManagedRecord();
        update.setId(record.getId());
        update.setVersion(record.getVersion());
        update.setEnabled(Boolean.FALSE);
        update.setSortOrder(20);
        update.setTenantId("tenant-a");

        assertThat(service.update(update)).isEqualTo(1);

        ManagedRecord selected = service.select(record.getId());
        assertThat(selected.getTenantId()).isEqualTo("tenant-a");
        assertThat(selected.getEnabled()).isFalse();
        assertThat(selected.getSortOrder()).isEqualTo(20);
        assertThat(selected.getCode()).isEqualTo("managed");
        assertThat(selected.getTitle()).isEqualTo("Managed");
        assertThat(selected.getVersion()).isEqualTo(1);
        assertThat(service.beforeUpdateCalls).isZero();
        assertThat(service.afterChangedModuleCode).isEqualTo("managed");
    }

    @Test
    void ordinaryMutationShouldNotChangeTenantOnPlatformManagedLightweightUpdate() {
        ManagedRecordService service = new ManagedRecordService();
        ManagedRecord record = managedRecord("managed", "Managed");
        record.setTenantId("tenant-a");
        record.setEnabled(Boolean.TRUE);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(record));

        ManagedRecord update = new ManagedRecord();
        update.setId(record.getId());
        update.setVersion(record.getVersion());
        update.setEnabled(Boolean.FALSE);
        update.setTenantId("tenant-b");

        assertThatThrownBy(() -> service.update(update))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
        ManagedRecord selected = service.select(record.getId());
        assertThat(selected.getTenantId()).isEqualTo("tenant-a");
        assertThat(selected.getEnabled()).isTrue();
    }

    @Test
    void ordinaryMutationShouldNotMarkExistingRecordAsPlatformManaged() {
        ManagedRecordService service = new ManagedRecordService();
        ManagedRecord record = new ManagedRecord();
        record.setCode("custom");
        record.setTitle("Custom");
        service.insert(record);

        ManagedRecord update = new ManagedRecord();
        update.setId(record.getId());
        update.setVersion(record.getVersion());
        update.setSystemManaged(Boolean.TRUE);

        assertThatThrownBy(() -> service.update(update))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
    }

    @Test
    void ordinaryMutationShouldUpdateNonManagedRecordNormally() {
        ManagedRecordService service = new ManagedRecordService();
        ManagedRecord record = new ManagedRecord();
        record.setCode("custom");
        record.setTitle("Custom");
        service.insert(record);

        ManagedRecord update = new ManagedRecord();
        update.setId(record.getId());
        update.setVersion(record.getVersion());
        update.setCode("changed");
        update.setTitle("Changed");

        assertThat(service.update(update)).isEqualTo(1);
        assertThat(service.select(record.getId()).getCode()).isEqualTo("changed");
        assertThat(service.beforeUpdateCalls).isEqualTo(1);
    }

    @Test
    void managedRecordWithoutEditableFieldsShouldNotExposeUpdateOrLifecycleActions() {
        LockedManagedRecordService service = new LockedManagedRecordService();
        ManagedRecord record = managedRecord("managed", "Managed");
        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(record));

        assertThat(service.ordinaryRecordActionAvailability("update", record))
                .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管记录不可编辑"));
        assertThat(service.ordinaryRecordActionAvailability("enable", record))
                .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管记录不可变更启用状态"));
        assertThat(service.ordinaryRecordActionAvailability("sort", record))
                .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管记录不可调整排序"));
    }

    private static ManagedRecord managedRecord(String code, String title) {
        ManagedRecord record = new ManagedRecord();
        record.setCode(code);
        record.setTitle(title);
        record.setSystemManaged(Boolean.TRUE);
        return record;
    }

    private static class ManagedRecordService extends AbstractAbilityService<ManagedRecord>
            implements PlatformManagedProtectionAbility<ManagedRecord> {
        private int beforeUpdateCalls;
        private String afterChangedModuleCode;

        private ManagedRecordService() {
            super("demo.platformManaged", ManagedRecord.class, new InMemoryBaseDao<>());
        }

        @Override
        public void beforeInsert(ManagedRecord entity) {
            normalize(entity);
        }

        @Override
        public void beforeUpdate(ManagedRecord entity) {
            beforeUpdateCalls++;
            normalize(entity);
        }

        @Override
        public void afterChanged(ManagedRecord entity) {
            afterChangedModuleCode = entity.getCode();
        }

        private void normalize(ManagedRecord entity) {
            if (entity.getSystemManaged() == null) {
                entity.setSystemManaged(Boolean.FALSE);
            }
        }
    }

    private static final class LockedManagedRecordService extends ManagedRecordService {
        @Override
        public Set<String> editablePlatformManagedFields() {
            return Set.of();
        }
    }

    @Table(name = "demo_managed_record", comment = "Demo managed record")
    private static final class ManagedRecord extends StandardEnabledSortableEntity implements PlatformManagedCapable {
        @Column(name = "code", type = ColumnType.VARCHAR, length = 64)
        private String code;

        @Column(name = "title", type = ColumnType.VARCHAR, length = 128)
        private String title;

        @Column(name = "system_managed", type = ColumnType.BOOLEAN)
        private Boolean systemManaged = Boolean.FALSE;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        @Override
        public Boolean getSystemManaged() {
            return systemManaged;
        }

        @Override
        public void setSystemManaged(Boolean systemManaged) {
            this.systemManaged = systemManaged;
        }
    }
}
