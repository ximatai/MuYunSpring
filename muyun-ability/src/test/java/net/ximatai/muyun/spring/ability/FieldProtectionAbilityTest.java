package net.ximatai.muyun.spring.ability;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldOutputRenderer;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.security.EncryptedField;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.security.FieldProtectionException;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldSignatureMode;
import net.ximatai.muyun.spring.common.security.MaskedField;
import net.ximatai.muyun.spring.common.security.SignedField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldProtectionAbilityTest {

    @Test
    void shouldProtectStaticAnnotatedFieldsOnlyDuringPersistence() {
        CopyingProtectedRecordDao dao = new CopyingProtectedRecordDao();
        ProtectedRecordService service = new ProtectedRecordService(dao);
        ProtectedDemoRecord record = new ProtectedDemoRecord();
        record.setTitle("Protected Demo");
        record.setPhone("13812345678");

        String id = service.insert(record);

        assertThat(record.getPhone()).isEqualTo("13812345678");
        assertThat(record.getPhoneSignature()).isNull();
        assertThat(dao.stored(id).getPhone()).isEqualTo("enc:13812345678");
        assertThat(dao.stored(id).getPhoneSignature()).isEqualTo("sig:phone:13812345678");

        ProtectedDemoRecord selected = service.select(id);
        ProtectedDemoRecord listed = service.list(Criteria.of(), PageRequest.of(1, 10)).getFirst();
        ProtectedDemoRecord paged = service.pageQuery(Criteria.of(), PageRequest.of(1, 10)).getRecords().getFirst();

        assertThat(selected.getPhone()).isEqualTo("13812345678");
        assertThat(listed.getPhone()).isEqualTo("13812345678");
        assertThat(paged.getPhone()).isEqualTo("13812345678");
        assertThat(selected.getPhoneSignature()).isEqualTo("sig:phone:13812345678");
        assertThat(service.maskProtectedValue("phone", selected.getPhone(), FieldOutputContext.VIEW))
                .isEqualTo("138****5678");
    }

    @Test
    void shouldRetainProtectedFieldWithoutMutatingTheRawSnapshot() {
        CopyingProtectedRecordDao dao = new CopyingProtectedRecordDao();
        ProtectedRecordService service = new ProtectedRecordService(dao);
        ProtectedDemoRecord record = new ProtectedDemoRecord();
        record.setPhone("13812345678");
        String id = service.insert(record);
        ProtectedDemoRecord stored = service.selectActiveRaw(id);
        ProtectedDemoRecord incoming = new ProtectedDemoRecord();
        incoming.setId(id);
        incoming.setVersion(stored.getVersion());

        service.retainProtectedFieldFromStorage(incoming, stored, "phone");
        assertThat(stored.getPhone()).isEqualTo("enc:13812345678");
        assertThat(incoming.getPhone()).isEqualTo("13812345678");
        service.update(incoming);
        assertThat(service.select(id).getPhone()).isEqualTo("13812345678");
        assertThat(dao.stored(id).getPhone()).isEqualTo("enc:13812345678");
    }

    @Test
    void shouldVerifyRetainedProtectedFieldsBeforeChangingTheIncomingRecord() {
        CopyingProtectedRecordDao dao = new CopyingProtectedRecordDao();
        ProtectedRecordService service = new ProtectedRecordService(dao);
        ProtectedDemoRecord record = new ProtectedDemoRecord();
        record.setPhone("13812345678");
        String id = service.insert(record);
        ProtectedDemoRecord stored = service.selectActiveRaw(id);
        stored.setPhoneSignature("tampered");
        ProtectedDemoRecord incoming = new ProtectedDemoRecord();

        assertThatThrownBy(() -> service.retainProtectedFieldFromStorage(incoming, stored, "phone"))
                .isInstanceOf(FieldProtectionException.class);
        assertThat(incoming.getPhone()).isNull();
        assertThat(stored.getPhone()).isEqualTo("enc:13812345678");
        assertThatThrownBy(() -> service.retainProtectedFieldFromStorage(incoming, stored, "title"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no storage protection");
    }

    @Test
    void shouldRejectTamperedStaticProtectedFieldSignature() {
        CopyingProtectedRecordDao dao = new CopyingProtectedRecordDao();
        ProtectedRecordService service = new ProtectedRecordService(dao);
        ProtectedDemoRecord record = new ProtectedDemoRecord();
        record.setPhone("13812345678");
        String id = service.insert(record);
        dao.stored(id).setPhoneSignature("sig:phone:tampered");

        assertThatThrownBy(() -> service.select(id))
                .isInstanceOf(FieldProtectionException.class)
                .hasMessageContaining("field signature mismatch: phone");
    }

    @Test
    void shouldProtectStaticReferenceProjections() {
        CopyingProtectedRecordDao dao = new CopyingProtectedRecordDao();
        ProtectedRecordService service = new ProtectedRecordService(dao);
        ProtectedDemoRecord record = new ProtectedDemoRecord();
        record.setTitle("Protected Demo");
        record.setPhone("13812345678");
        String id = service.insert(record);

        Map<String, Map<String, Object>> projections = service.projections(List.of(id), List.of("phone"));

        assertThat(projections.get(id)).containsEntry("phone", "138****5678");
        assertThat(service.title(id)).isEqualTo("P************o");
        assertThat(service.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords().getFirst().title())
                .isEqualTo("P************o");
    }

    @Test
    void shouldClearStaticProtectedFieldSignatureWhenValueIsNull() {
        CopyingProtectedRecordDao dao = new CopyingProtectedRecordDao();
        ProtectedRecordService service = new ProtectedRecordService(dao);
        ProtectedDemoRecord record = new ProtectedDemoRecord();
        record.setTitle("Protected Demo");
        record.setPhone("13812345678");
        String id = service.insert(record);
        ProtectedDemoRecord selected = service.select(id);
        selected.setPhone(null);

        service.update(selected);

        assertThat(dao.stored(id).getPhone()).isNull();
        assertThat(dao.stored(id).getPhoneSignature()).isNull();
    }

    @Test
    void shouldProtectStaticFieldsWhenReadingIgnoringSoftDeleteAndDeleting() {
        CopyingProtectedRecordDao dao = new CopyingProtectedRecordDao();
        ProtectedRecordService service = new ProtectedRecordService(dao);
        ProtectedDemoRecord record = new ProtectedDemoRecord();
        record.setPhone("13812345678");
        String id = service.insert(record);

        ProtectedDemoRecord ignoredSoftDelete = service.selectIgnoreSoftDelete(id);
        service.delete(id);

        assertThat(ignoredSoftDelete.getPhone()).isEqualTo("13812345678");
        assertThat(dao.stored(id).getDeleted()).isTrue();
        assertThat(dao.stored(id).getPhone()).isEqualTo("enc:13812345678");
        assertThat(dao.stored(id).getPhoneSignature()).isEqualTo("sig:phone:13812345678");
    }

    @Test
    void shouldRenderProtectedValueThroughUnifiedFieldOutputRenderer() {
        Object rendered = FieldOutputRenderer.renderValue(
                "phone",
                "13812345678",
                new FieldProtectionDefinition(
                        FieldEncryptionMode.NONE,
                        FieldSignatureMode.NONE,
                        FieldMaskingPolicy.PHONE
                ),
                FieldOutputContext.VIEW,
                null
        );

        assertThat(rendered).isEqualTo("138****5678");
    }

    private static final class ProtectedRecordService extends AbstractAbilityService<ProtectedDemoRecord>
            implements FieldProtectionAbility<ProtectedDemoRecord>,
            SoftDeleteAbility<ProtectedDemoRecord>,
            ReferenceAbility<ProtectedDemoRecord> {
        private static final FieldCryptoProvider CRYPTO = new FieldCryptoProvider() {
            @Override
            public String encrypt(String fieldName, Object plainValue) {
                return "enc:" + plainValue;
            }

            @Override
            public Object decrypt(String fieldName, String protectedValue) {
                return protectedValue.substring("enc:".length());
            }
        };
        private static final FieldSigner SIGNER = (fieldName, plainValue) -> "sig:" + fieldName + ":" + plainValue;

        ProtectedRecordService(BaseDao<ProtectedDemoRecord, String> dao) {
            super("demo.protectedRecord", ProtectedDemoRecord.class, dao);
        }

        @Override
        public FieldCryptoProvider fieldCryptoProvider() {
            return CRYPTO;
        }

        @Override
        public FieldSigner fieldSigner() {
            return SIGNER;
        }
    }

    @Getter
    @Setter
    private static final class ProtectedDemoRecord extends StandardEntity implements TitledCapable {
        @MaskedField(FieldMaskingPolicy.MIDDLE)
        private String title;
        @EncryptedField
        @SignedField
        @MaskedField(FieldMaskingPolicy.PHONE)
        private String phone;
        private String phoneSignature;
    }

    private static final class CopyingProtectedRecordDao implements BaseDao<ProtectedDemoRecord, String> {
        private final Map<String, ProtectedDemoRecord> rows = new LinkedHashMap<>();

        @Override
        public boolean ensureTable() {
            return true;
        }

        @Override
        public String insert(ProtectedDemoRecord entity) {
            rows.put(entity.getId(), copy(entity));
            return entity.getId();
        }

        @Override
        public int updateById(ProtectedDemoRecord entity) {
            rows.put(entity.getId(), copy(entity));
            return 1;
        }

        @Override
        public int updateByIdAndCondition(ProtectedDemoRecord entity, Map<String, Object> conditions) {
            return updateById(entity);
        }

        @Override
        public int deleteById(String id) {
            return rows.remove(id) == null ? 0 : 1;
        }

        @Override
        public int deleteByIdAndCondition(String id, Map<String, Object> conditions) {
            return deleteById(id);
        }

        @Override
        public boolean existsById(String id) {
            return rows.containsKey(id);
        }

        @Override
        public ProtectedDemoRecord findById(String id) {
            ProtectedDemoRecord record = rows.get(id);
            return record == null ? null : copy(record);
        }

        @Override
        public List<ProtectedDemoRecord> query(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            return rows.values().stream().map(this::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        @Override
        public List<ProtectedDemoRecord> list(Criteria criteria, Sort... sorts) {
            return rows.values().stream().map(this::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        @Override
        public PageResult<ProtectedDemoRecord> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            List<ProtectedDemoRecord> records = query(criteria, pageRequest, sorts);
            return PageResult.of(records, records.size(), pageRequest);
        }

        @Override
        public long count(Criteria criteria) {
            return rows.size();
        }

        @Override
        public int upsert(ProtectedDemoRecord entity) {
            rows.put(entity.getId(), copy(entity));
            return 1;
        }

        ProtectedDemoRecord stored(String id) {
            return rows.get(id);
        }

        private ProtectedDemoRecord copy(ProtectedDemoRecord source) {
            ProtectedDemoRecord copy = new ProtectedDemoRecord();
            copy.setId(source.getId());
            copy.setTenantId(source.getTenantId());
            copy.setVersion(source.getVersion());
            copy.setDeleted(source.getDeleted());
            copy.setDeletedAt(source.getDeletedAt());
            copy.setCreatedBy(source.getCreatedBy());
            copy.setCreatedAt(source.getCreatedAt());
            copy.setUpdatedBy(source.getUpdatedBy());
            copy.setUpdatedAt(source.getUpdatedAt());
            copy.setTitle(source.getTitle());
            copy.setPhone(source.getPhone());
            copy.setPhoneSignature(source.getPhoneSignature());
            return copy;
        }
    }
}
