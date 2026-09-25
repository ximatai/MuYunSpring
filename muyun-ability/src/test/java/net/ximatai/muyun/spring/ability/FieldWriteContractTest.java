package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.constraint.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FieldWriteContractTest {
    @Test
    void standardWritesEnforceInheritedRulesEvenWhenBusinessHooksAreOverridden() {
        Service service = new Service();
        Record record = valid();
        record.code = "  C-1  ";
        record.description = " \t ";
        service.insert(record);
        assertThat(record.code).isEqualTo("C-1");
        assertThat(record.description).isNull();
        assertThat(service.codeSeenByHook).isEqualTo("C-1");

        Record blank = valid();
        blank.code = " \n ";
        assertThatThrownBy(() -> service.insert(blank)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    void operationComesFromWriteEntryNotWhetherAnIdWasPreallocated() {
        Service service = new Service();
        Record record = valid();
        record.setId("preallocated");
        record.insertToken = null;
        assertThatThrownBy(() -> service.insert(record)).hasMessageContaining("insertToken");
        record.insertToken = "secret with spaces ";
        service.insert(record);
        assertThat(record.insertToken).isEqualTo("secret with spaces ");

        Record update = valid();
        update.setId(record.getId());
        update.setVersion(record.getVersion());
        update.insertToken = null;
        assertThatThrownBy(() -> service.update(update)).hasMessageContaining("updateReason");
        update.updateReason = "Changed";
        update.setVersion(record.getVersion());
        assertThat(service.update(update)).isEqualTo(1);
    }

    @Test
    void finalValidationSeesGeneratedValuesAndRejectsInvalidValuesIntroducedByHooks() {
        Service service = new Service();
        service.generatedCode = "  GENERATED  ";
        Record record = valid();
        record.code = null;
        service.insert(record);
        assertThat(record.code).isEqualTo("GENERATED");
        service.generatedCode = "  ";
        assertThatThrownBy(() -> service.insert(valid())).hasMessageContaining("code");
    }

    @Test
    void fullUpdateDoesNotSilentlyRetainMissingFields() {
        Service service = new Service();
        Record stored = valid();
        service.insert(stored);
        Record update = valid();
        update.setId(stored.getId());
        update.setVersion(stored.getVersion());
        update.updateReason = "Change";
        update.code = null;
        assertThatThrownBy(() -> service.update(update)).hasMessageContaining("code");
    }

    @Test
    void falseAndZeroAreValidAndInvalidNormalizationDeclarationsFailAtConstruction() {
        new Service().insert(valid());
        assertThatThrownBy(() -> new StandardBusinessService<InvalidRecord>("test.invalid", InvalidRecord.class,
                new InMemoryBaseDao<>()) {}).hasMessageContaining("string field");
    }

    private static Record valid() {
        Record record = new Record();
        record.code = "C-1";
        record.insertToken = "token";
        record.flag = false;
        record.count = 0;
        return record;
    }

    static class ParentRecord extends StandardEntity {
        @Required @NormalizeText String code;
    }

    static class Record extends ParentRecord {
        @Required(on = WriteOperation.INSERT) String insertToken;
        @Required(on = WriteOperation.UPDATE) String updateReason;
        @NormalizeText(TextNormalization.TRIM_TO_NULL) String description;
        @Required Boolean flag;
        @Required Integer count;
    }

    static class InvalidRecord extends StandardEntity {
        @NormalizeText Integer number;
    }

    static class Service extends StandardBusinessService<Record> {
        String codeSeenByHook;
        String generatedCode;
        Service() { super("test.write_rules", Record.class, new InMemoryBaseDao<>()); }
        @Override public void beforeInsert(Record record) {
            codeSeenByHook = record.code;
            if (generatedCode != null) record.code = generatedCode;
        }
        @Override public void beforeUpdate(Record record, Record existing) {}
    }
}
