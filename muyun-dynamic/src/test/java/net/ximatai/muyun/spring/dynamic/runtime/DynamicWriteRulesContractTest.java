package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DynamicWriteRulesContractTest {
    private static final EntityDefinition ENTITY = new EntityDefinition("entry", "app_write_entry", "Entry",
            List.of(FieldDefinition.string("code", "Code").required(), FieldDefinition.string("note", "Note")));

    @Test
    void legacyStorageNullRequirementUsesTheUnbypassableSaveChainAndPreservesBlankSemantics() {
        var dao = new DynamicRecordDao(operations(), ENTITY);
        var service = new DynamicEntityService(dao, "test.entries") {
            @Override public void beforeInsert(DynamicRecord record) { }
        };
        assertThatThrownBy(() -> service.insert(new DynamicRecord(ENTITY))).hasMessageContaining("code must not be null");
        assertThatThrownBy(() -> service.insert(new DynamicRecord(ENTITY).setValue("code", null)))
                .hasMessageContaining("code must not be null");
        assertThatCode(() -> service.insert(new DynamicRecord(ENTITY).setValue("code", " "))).doesNotThrowAnyException();
    }

    @Test
    void nullDraftCanBeFilledByLifecycleBeforeFinalValidation() {
        var service = DynamicEntityService.withLifecycle(new DynamicRecordDao(operations(), ENTITY), "test.entries",
                new DynamicRecordLifecycle() {
                    @Override public void beforeInsert(DynamicRecord record) { record.putGeneratedValue("code", "GENERATED"); }
                });
        DynamicRecord record = new DynamicRecord(ENTITY).setValue("code", null);
        service.insert(record);
        assertThat(record.getValue("code")).isEqualTo("GENERATED");
    }

    @Test
    void partialUpdatePreservesOmittedRequiredValuesButCannotExplicitlyClearThem() {
        var operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of("id", "one", "version", 0,
                "code", "EXISTING", "deleted", false)));
        var service = new DynamicEntityService(new DynamicRecordDao(operations, ENTITY), "test.entries");
        DynamicRecord omitted = new DynamicRecord(ENTITY).setValue("note", "changed");
        omitted.setId("one");
        omitted.setVersion(0);
        assertThat(service.update(omitted)).isEqualTo(1);
        assertThat(omitted.getPlatformValues()).doesNotContainKey("code");
        DynamicRecord cleared = new DynamicRecord(ENTITY).setValue("code", null);
        cleared.setId("one");
        cleared.setVersion(0);
        assertThatThrownBy(() -> service.update(cleared)).hasMessageContaining("code must not be null");
    }

    @Test
    void operationSpecificRuleChecksGeneratedFinalValueEvenWithAnOverriddenUpdateHook() {
        EntityDefinition entity = new EntityDefinition("entry", "app_write_entry", "Entry", List.of(
                FieldDefinition.string("code", "Code").writeRules(new FieldWriteRules(false, true, TextNormalization.TRIM))));
        var operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of("id", "one", "version", 0, "deleted", false)));
        var service = new DynamicEntityService(new DynamicRecordDao(operations, entity), "test.entries") {
            @Override public void beforeUpdate(DynamicRecord record, DynamicRecord existing) { record.putGeneratedValue("code", "  "); }
        };
        service.insert(new DynamicRecord(entity));
        DynamicRecord update = new DynamicRecord(entity);
        update.setId("one"); update.setVersion(0);
        assertThatThrownBy(() -> service.update(update)).hasMessageContaining("code must not be blank");
    }

    @Test
    void omittedEncryptedValueIsValidatedAsBusinessTextWithoutRewritingThePatch() {
        var protection = new net.ximatai.muyun.spring.common.security.FieldProtectionDefinition(
                net.ximatai.muyun.spring.common.security.FieldEncryptionMode.ENCRYPTED, null, null);
        EntityDefinition entity = new EntityDefinition("entry", "app_write_entry", "Entry", List.of(
                FieldDefinition.string("code", "Code").protection(protection)
                        .writeRules(new FieldWriteRules(false, true, TextNormalization.NONE)),
                FieldDefinition.string("note", "Note")));
        var operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of("id", "one", "version", 0,
                "code", "ciphertext", "deleted", false)));
        var crypto = mock(net.ximatai.muyun.spring.ability.security.FieldCryptoProvider.class);
        when(crypto.decrypt("code", "ciphertext")).thenReturn("  ");
        var service = new DynamicEntityService(new DynamicRecordDao(operations, entity), "test.entries") {
            @Override public net.ximatai.muyun.spring.ability.security.FieldCryptoProvider fieldCryptoProvider() { return crypto; }
        };
        DynamicRecord patch = new DynamicRecord(entity).setValue("note", "changed");
        patch.setId("one"); patch.setVersion(0);
        assertThatThrownBy(() -> service.update(patch)).hasMessageContaining("code must not be blank");
        assertThat(patch.getPlatformValues()).doesNotContainKey("code");
        verify(crypto).decrypt("code", "ciphertext");
        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void omittedEncryptedStorageValueIsNotNormalizedByNewWriteRules() {
        var protection = new net.ximatai.muyun.spring.common.security.FieldProtectionDefinition(
                net.ximatai.muyun.spring.common.security.FieldEncryptionMode.ENCRYPTED, null, null);
        EntityDefinition entity = new EntityDefinition("entry", "app_write_entry", "Entry", List.of(
                FieldDefinition.string("code", "Code").required().protection(protection)
                        .writeRules(new FieldWriteRules(false, false, TextNormalization.TRIM_TO_NULL)),
                FieldDefinition.string("note", "Note")));
        var operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of("id", "one", "version", 0,
                "code", "ciphertext", "deleted", false)));
        var crypto = mock(net.ximatai.muyun.spring.ability.security.FieldCryptoProvider.class);
        when(crypto.decrypt("code", "ciphertext")).thenReturn("  ");
        var service = new DynamicEntityService(new DynamicRecordDao(operations, entity), "test.entries") {
            @Override public net.ximatai.muyun.spring.ability.security.FieldCryptoProvider fieldCryptoProvider() { return crypto; }
        };
        DynamicRecord patch = new DynamicRecord(entity).setValue("note", "changed");
        patch.setId("one"); patch.setVersion(0);
        assertThat(service.update(patch)).isEqualTo(1);
        assertThat(patch.getPlatformValues()).doesNotContainKey("code");
        verify(crypto).decrypt("code", "ciphertext");
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> operations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL"));
        when(operations.getDefaultSchemaName()).thenReturn("public");
        when(operations.insertItem(anyString(), anyString(), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), eq("id"))).thenReturn(1);
        return operations;
    }
}
