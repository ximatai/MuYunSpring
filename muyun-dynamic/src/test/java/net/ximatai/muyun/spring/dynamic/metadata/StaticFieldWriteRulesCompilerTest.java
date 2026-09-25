package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.constraint.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaticFieldWriteRulesCompilerTest {
    @Test
    void staticDeclarationsCompileToTheSameRulesWithoutChangingSchemaNullability() {
        EntityDefinition entity = new StaticEntityDefinitionCompiler().compile("entry", "Entry", Entry.class);
        FieldDefinition code = entity.fields().stream().filter(field -> field.code().equals("code")).findFirst().orElseThrow();
        assertThat(code.isRequired()).isFalse();
        assertThat(code.behavior().writeRules()).isEqualTo(new FieldWriteRules(true, false, TextNormalization.TRIM));
        FieldDefinition storageOnly = entity.fields().stream().filter(field -> field.code().equals("storageOnly")).findFirst().orElseThrow();
        assertThat(storageOnly.isRequired()).isTrue();
        assertThat(storageOnly.behavior().writeRules()).isEqualTo(FieldWriteRules.NONE);
        assertThat(code.defaultValue(" C ").validationRegex("[A-Z]+").notCopyable().writeProtected()
                .behavior().writeRules()).isEqualTo(code.behavior().writeRules());
    }

    static class Parent extends StandardEntity {
        @Required(on = WriteOperation.INSERT) @NormalizeText
        @Column(name = "code", type = ColumnType.VARCHAR)
        private String code;
    }

    @Table(name = "write_rule_entry")
    static class Entry extends Parent {
        @Column(name = "storage_only", type = ColumnType.VARCHAR, nullable = false)
        private String storageOnly;
    }
}
