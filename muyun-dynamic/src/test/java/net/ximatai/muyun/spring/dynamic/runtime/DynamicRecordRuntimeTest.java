package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.SqlRawCondition;
import net.ximatai.muyun.spring.ability.CacheRegistry;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.security.FieldProtectionException;
import net.ximatai.muyun.spring.common.security.FieldSignatureMode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicRecordRuntimeTest {
    private static final String SCHEMA = "public";
    private static final String TABLE = "app_contract";

    @Test
    void nestedChildLookupUsesTheSameDefinitionSnapshotAsItsParent() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        var parent = new EntityDefinition("parent", "snapshot_parent", "Parent", List.of());
        var oldChild = new EntityDefinition("child", "snapshot_child_old", "Child", List.of(
                FieldDefinition.string("parentId", "Parent").column("parent_id")));
        var newChild = new EntityDefinition("child", "snapshot_child_new", "Child", oldChild.fields());
        var relation = net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition.child(
                "children", "parent", "child", "parentId");
        var runtime = new DynamicRecordRuntime(operations).register(ModuleDefinition.builder("test.snapshot", "Snapshot")
                .entities(List.of(parent, oldChild)).relations(List.of(relation)).build());
        var original = runtime.entityService("test.snapshot", "parent");
        runtime.refresh(ModuleDefinition.builder("test.snapshot", "Snapshot")
                .entities(List.of(parent, newChild)).relations(List.of(relation)).build());
        original.childRelations().getFirst().selectChildren("parent-1");
        runtime.entityService("test.snapshot", "parent").childRelations().getFirst().selectChildren("parent-1");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.times(2)).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues().get(0)).contains("snapshot_child_old").doesNotContain("snapshot_child_new");
        assertThat(sql.getAllValues().get(1)).contains("snapshot_child_new").doesNotContain("snapshot_child_old");
    }

    @Test
    void shouldCreateEntityServiceFromRegisteredModule() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations)
                .register(contractModule());

        DynamicEntityService entityService = runtime.entityService("sales.contract", "contract");
        DynamicRecord record = runtime.newRecord("sales.contract", "contract")
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);

        String id = entityService.insert(record);

        assertThat(id).hasSize(32);
        assertThat(entityService.getModuleAlias()).isEqualTo("sales.contract");
        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("id", id)
                .containsEntry("code", "C-001")
                .containsEntry("amount", BigDecimal.TEN)
                .containsEntry("version", 0)
                .containsEntry("deleted", Boolean.FALSE);
    }

    @Test
    void shouldRunDynamicCrudThroughRuntimeServiceChain() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "amount", BigDecimal.TEN,
                "deleted", Boolean.FALSE,
                "version", 0
        )));
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations)
                .register(contractModule());
        DynamicEntityService entityService = runtime.entityService("sales.contract", "contract");
        DynamicRecord record = runtime.newRecord("sales.contract", "contract")
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);
        record.setId("contract-1");

        String id = entityService.insert(record);
        DynamicRecord selected = entityService.select(id);
        selected.setValue("amount", BigDecimal.ONE);
        entityService.update(selected);
        entityService.pageQuery(Criteria.of().eq("code", "C-001"), PageRequest.of(1, 10));
        entityService.delete(id);

        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id"));
        verify(operations, org.mockito.Mockito.times(2))
                .patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), anyMap(), anyMap(), eq("id"));
        ArgumentCaptor<String> querySql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(querySql.capture(), anyMap());
        assertThat(querySql.getAllValues()).anySatisfy(sql -> assertThat(sql)
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shouldPopulateOptionLoadOnActiveCachedSelectAndRetainedPage(boolean retained) {
        CacheRegistry.clearAll();
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "teacher-1",
                "subject_code", "mathematics",
                "deleted", retained,
                "version", 0
        )));
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .optionLoadPopulator((entity, records) -> records.forEach(record ->
                        record.putVirtualValue("subjectTitle", "数学")))
                .build()
                .register(new ModuleDefinition("education.teacher", "Teacher", List.of(new EntityDefinition(
                        "teacher", "education_teacher", "Teacher", List.of(
                        FieldDefinition.string("subjectCode", "Subject").column("subject_code")
                                .dictionary("education", "teaching_subject"),
                        FieldDefinition.string("subjectTitle", "Subject Title").column("subject_title")
                                .virtual().optionLoad("subjectCode")
                ), java.util.Set.of(EntityCapability.RECYCLE_BIN)))));
        DynamicEntityService service = runtime.entityService("education.teacher", "teacher");

        for (int i = 0; i < 2; i++) {
            DynamicRecord record = retained ? service.pageRecycleBin(Criteria.of(), PageRequest.of(1, 10))
                    .getRecords().getFirst() : service.select("teacher-1");
            assertThat(record.getValue("subjectTitle")).isEqualTo("数学");
        }
    }

    @Test
    void shouldClearRuntimeCacheByNamespacePrefix() {
        CacheRegistry.clearAll();
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "amount", BigDecimal.TEN,
                "deleted", Boolean.FALSE,
                "version", 0
        )));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations)
                .register(contractModule());
        DynamicEntityService entityService = runtime.entityService("sales.contract", "contract");

        assertThat(entityService.select("contract-1")).isNotNull();
        assertThat(CacheRegistry.namespaceCount()).isEqualTo(1);

        runtime.close();

        assertThat(CacheRegistry.namespaceCount()).isZero();
    }

    @Test
    void shouldApplyDynamicFieldProtectionAcrossCrudReadsAndDescriptors() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "title", "Contract One",
                "secret", "enc:sensitive-value",
                "secret_signature", "sig:secret:sensitive-value",
                "deleted", Boolean.FALSE,
                "version", 0
        )));
        DynamicRecordRuntime runtime = protectedRuntime(operations).register(protectedContractModule());
        DynamicEntityService entityService = runtime.entityService("sales.contract", "contract");
        DynamicRecord record = runtime.newRecord("sales.contract", "contract")
                .setValue("code", "C-001")
                .setValue("secret", "sensitive-value");
        record.setId("contract-1");

        entityService.insert(record);
        DynamicRecord selected = entityService.select("contract-1");
        DynamicRecord paged = entityService.pageQuery(Criteria.of(), PageRequest.of(1, 10)).getRecords().getFirst();
        Map<String, Map<String, Object>> projections = entityService.projections(List.of("contract-1"), List.of("secret"));

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("secret", "enc:sensitive-value")
                .containsEntry("secret_signature", "sig:secret:sensitive-value");
        assertThat(record.getValue("secret")).isEqualTo("sensitive-value");
        assertThat(record.getValues()).doesNotContainKey("secretSignature");
        assertThatThrownBy(() -> record.getValue("secretSignature"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform managed");
        assertThat(selected.getValue("secret")).isEqualTo("sensitive-value");
        assertThat(paged.getValue("secret")).isEqualTo("sensitive-value");
        assertThat(projections.get("contract-1").get("secret"))
                .isNotEqualTo("sensitive-value")
                .asString()
                .startsWith("s")
                .endsWith("e");
        assertThat(runtime.describe("sales.contract").entities().getFirst().fields())
                .anySatisfy(field -> {
                    assertThat(field.fieldName()).isEqualTo("secret");
                    assertThat(field.encrypted()).isTrue();
                    assertThat(field.signed()).isTrue();
                    assertThat(field.maskingPolicy()).isEqualTo("MIDDLE");
                    assertThat(field.companions()).extracting(companion -> companion.fieldName())
                            .containsExactly("secretSignature");
                });
        assertThatThrownBy(() -> entityService.pageQuery(
                Criteria.of().eq("secretSignature", "sig:secret:sensitive-value"),
                PageRequest.of(1, 10)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected storage field cannot be used in dynamic query or sort");
    }

    @Test
    void shouldRejectDynamicQueryAndSortByProtectedStorageField() {
        IDatabaseOperations<Object> operations = operations();
        DynamicEntityService entityService = protectedRuntime(operations)
                .register(protectedContractModule())
                .entityService("sales.contract", "contract");

        assertThatThrownBy(() -> entityService.pageQuery(Criteria.of().eq("secret", "sensitive-value"),
                PageRequest.of(1, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected storage field cannot be used in dynamic query or sort: secret");
        assertThatThrownBy(() -> entityService.pageQuery(Criteria.of(), PageRequest.of(1, 10), Sort.asc("secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected storage field cannot be used in dynamic query or sort: secret");
        assertThatThrownBy(() -> entityService.pageQuery(Criteria.of().eq("secret_signature", "sig"),
                PageRequest.of(1, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected storage field cannot be used in dynamic query or sort: secret_signature");
        assertThatThrownBy(() -> entityService.pageQuery(
                Criteria.of().raw(SqlRawCondition.of("\"secret_signature\" = :signature", Map.of("signature", "sig"))),
                PageRequest.of(1, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("raw criteria cannot be used when dynamic entity has protected storage fields");
    }

    @Test
    void shouldMaskDynamicReferenceTitleWhenTitleFieldHasOutputProtection() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "title", "Sensitive Contract",
                "deleted", Boolean.FALSE,
                "version", 0
        )));
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        DynamicEntityService entityService = protectedRuntime(operations)
                .register(maskedTitleContractModule())
                .entityService("sales.contract", "contract");

        assertThat(entityService.title("contract-1")).isEqualTo("S****************t");
        assertThat(entityService.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords().getFirst().title())
                .isEqualTo("S****************t");
    }

    @Test
    void recycleBinMustDecryptAndVerifyRetainedRecordsAndRestoreThroughEntityLifecycle() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        Map<String, Object> row = new java.util.LinkedHashMap<>(Map.of(
                "id", "contract-1", "secret", "enc:sensitive-value",
                "secret_signature", "sig:secret:sensitive-value", "deleted", true, "version", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row));
        DynamicRecordRuntime runtime = protectedRuntime(operations).register(protectedContractModule());
        DynamicEntityOperations records = new DynamicRecordService(runtime).mainEntity("sales.contract");

        assertThat(records.pageRecycleBin(Criteria.of(), PageRequest.of(1, 10)).getRecords().getFirst().getValue("secret"))
                .isEqualTo("sensitive-value");
        assertThat(records.pageRecycleBin(Criteria.of().eq("id", "contract-1"), PageRequest.of(1, 1))
                .getRecords().getFirst().getValue("secret")).isEqualTo("sensitive-value");
        assertThat(records.restore("contract-1")).isEqualTo(1);
        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), anyMap(), eq("id"));
        assertThat(body.getValue()).containsEntry("secret", "enc:sensitive-value")
                .containsEntry("secret_signature", "sig:secret:sensitive-value").containsEntry("deleted", false);
        row.put("secret_signature", "tampered");
        assertThatThrownBy(() -> records.pageRecycleBin(Criteria.of(), PageRequest.of(1, 10)))
                .isInstanceOf(FieldProtectionException.class);
    }

    @Test
    void shouldRejectTamperedDynamicProtectedFieldSignature() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "title", "Contract One",
                "secret", "enc:sensitive-value",
                "secret_signature", "sig:secret:tampered",
                "deleted", Boolean.FALSE,
                "version", 0
        )));
        DynamicEntityService entityService = protectedRuntime(operations)
                .register(protectedContractModule())
                .entityService("sales.contract", "contract");

        assertThatThrownBy(() -> entityService.select("contract-1"))
                .isInstanceOf(FieldProtectionException.class)
                .hasMessageContaining("field signature mismatch: secret");
    }

    @Test
    void shouldApplyDynamicFieldProtectionWhenReadingSortedList() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "secret", "enc:sensitive-value",
                "secret_signature", "sig:secret:sensitive-value",
                "sort_order", 100,
                "deleted", Boolean.FALSE,
                "version", 0
        )));
        DynamicEntityService entityService = protectedRuntime(operations)
                .register(protectedSortableContractModule())
                .entityService("sales.contract", "contract");

        List<DynamicRecord> records = entityService.sortedList(Criteria.of());

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getValue("secret")).isEqualTo("sensitive-value");
    }

    @Test
    void shouldClearDynamicProtectedFieldSignatureWhenExplicitlySetToNull() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "secret", "enc:sensitive-value",
                "secret_signature", "sig:secret:sensitive-value",
                "deleted", Boolean.FALSE,
                "version", 0
        )));
        DynamicRecordRuntime runtime = protectedRuntime(operations)
                .register(protectedContractModule());
        DynamicEntityService entityService = runtime.entityService("sales.contract", "contract");
        DynamicRecord record = runtime.newRecord("sales.contract", "contract").setValue("secret", null);
        record.setId("contract-1");
        record.setVersion(0);

        entityService.update(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), anyMap(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("secret", null)
                .containsEntry("secret_signature", null);
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> operations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn(SCHEMA);
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        return operations;
    }

    private ModuleDefinition contractModule() {
        return new ModuleDefinition("sales.contract", "Contract", List.of(contractEntity()));
    }

    private DynamicRecordRuntime protectedRuntime(IDatabaseOperations<Object> operations) {
        FieldCryptoProvider crypto = new FieldCryptoProvider() {
            @Override
            public String encrypt(String fieldName, Object plainValue) {
                return "enc:" + plainValue;
            }

            @Override
            public Object decrypt(String fieldName, String protectedValue) {
                return protectedValue.substring("enc:".length());
            }
        };
        return DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(RuntimeEventPublisher.noop())
                .actionExecutorRegistry(DynamicActionExecutorRegistry.empty())
                .actionTransactionOperator(DynamicActionTransactionOperator.none())
                .fieldProtection(crypto,
                        (fieldName, plainValue) -> "sig:" + fieldName + ":" + plainValue)
                .build();
    }

    private ModuleDefinition protectedContractModule() {
        return new ModuleDefinition("sales.contract", "Contract", List.of(new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.titleField(),
                        FieldDefinition.string("code", "Code").length(64),
                        FieldDefinition.string("secret", "Secret")
                                .length(128)
                                .protection(new FieldProtectionDefinition(
                                        FieldEncryptionMode.ENCRYPTED,
                                        FieldSignatureMode.SIGNED,
                                        FieldMaskingPolicy.MIDDLE
                                ))
                ),
                java.util.Set.of(EntityCapability.REFERENCE, EntityCapability.RECYCLE_BIN)
        )));
    }

    private ModuleDefinition protectedSortableContractModule() {
        return new ModuleDefinition("sales.contract", "Contract", List.of(new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64),
                        FieldDefinition.string("secret", "Secret")
                                .length(128)
                                .protection(new FieldProtectionDefinition(
                                        FieldEncryptionMode.ENCRYPTED,
                                        FieldSignatureMode.SIGNED,
                                        FieldMaskingPolicy.MIDDLE
                                )),
                        FieldDefinition.sortOrder()
                ),
                java.util.Set.of(EntityCapability.SORT)
        )));
    }

    private ModuleDefinition maskedTitleContractModule() {
        return new ModuleDefinition("sales.contract", "Contract", List.of(new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.titleField()
                                .protection(new FieldProtectionDefinition(
                                        FieldEncryptionMode.NONE,
                                        FieldSignatureMode.NONE,
                                        FieldMaskingPolicy.MIDDLE
                                ))
                ),
                java.util.Set.of(EntityCapability.REFERENCE)
        )));
    }

    private EntityDefinition contractEntity() {
        return new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                )
        );
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
