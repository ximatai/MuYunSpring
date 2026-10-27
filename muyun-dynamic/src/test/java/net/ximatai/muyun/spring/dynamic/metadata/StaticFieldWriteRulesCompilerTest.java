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
        assertThat(code.behavior().validationRegex()).isEqualTo("[A-Z]+");
        assertThat(code.behavior().writeRules()).isEqualTo(new FieldWriteRules(true, false, TextNormalization.TRIM));
        FieldDefinition storageOnly = entity.fields().stream().filter(field -> field.code().equals("storageOnly")).findFirst().orElseThrow();
        assertThat(storageOnly.isRequired()).isTrue();
        assertThat(storageOnly.behavior().writeRules()).isEqualTo(FieldWriteRules.NONE);
        assertThat(code.defaultValue(" C ").validationRegex("[A-Z]+").notCopyable().writeProtected()
                .behavior().writeRules()).isEqualTo(code.behavior().writeRules());
    }

    @Test
    void compilesOnlyDeclaredLiteralDefaultsWithoutConstructingModels() {
        var entity = new StaticEntityDefinitionCompiler().compile("defaults", "Defaults", Defaults.class);
        var defaults = entity.fields().stream().filter(field -> field.behavior().defaultValue() != null)
                .collect(java.util.stream.Collectors.toMap(FieldDefinition::fieldName, field -> field.behavior().defaultValue()));
        assertThat(defaults).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "enabled", "true", "disabled", "false", "count", "0", "ratio", "1.25", "mode", "disabled"));
    }

    @Table(name = "literal_defaults")
    static class Defaults extends StandardEntity {
        Defaults() { throw new AssertionError("metadata compilation must not construct models"); }
        @Column(type = ColumnType.BOOLEAN, defaultVal = @net.ximatai.muyun.database.core.annotation.Default(bool = net.ximatai.muyun.database.core.annotation.TrueOrFalse.TRUE))
        Boolean enabled;
        @Column(type = ColumnType.BOOLEAN, defaultVal = @net.ximatai.muyun.database.core.annotation.Default(bool = net.ximatai.muyun.database.core.annotation.TrueOrFalse.FALSE))
        Boolean disabled;
        @Column(type = ColumnType.INT, defaultVal = @net.ximatai.muyun.database.core.annotation.Default(number = 0))
        Integer count;
        @Column(type = ColumnType.NUMERIC, defaultVal = @net.ximatai.muyun.database.core.annotation.Default(decimal = 1.25))
        java.math.BigDecimal ratio;
        @Column(defaultVal = @net.ximatai.muyun.database.core.annotation.Default(varchar = "disabled"))
        String mode;
        @Column(defaultVal = @net.ximatai.muyun.database.core.annotation.Default(function = "CURRENT_TIMESTAMP"))
        String generated;
        @Column(defaultVal = @net.ximatai.muyun.database.core.annotation.Default(express = "1 + 1"))
        Integer expression;
        @Column String absent;
    }

    static class Parent extends StandardEntity {
        @Required(on = WriteOperation.INSERT) @NormalizeText @FieldPattern("[A-Z]+")
        @Column(name = "code", type = ColumnType.VARCHAR)
        private String code;
    }

    @Table(name = "write_rule_entry")
    static class Entry extends Parent {
        @Column(name = "storage_only", type = ColumnType.VARCHAR, nullable = false)
        private String storageOnly;
    }
}
