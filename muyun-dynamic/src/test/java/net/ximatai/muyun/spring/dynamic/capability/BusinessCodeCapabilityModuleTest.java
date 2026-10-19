package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.code.*;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BusinessCodeCapabilityModuleTest {
    private EntityDefinition entity() { return new EntityDefinition("invoice", "invoice", "单据", List.of(
            FieldDefinition.string("documentNo", "编码").column("document_no").length(32),
            FieldDefinition.integer("quantity", "数量").column("quantity"))); }
    @Test void dynamicDeclarationDerivesCapabilityAndSurvivesDefinitionCopies() {
        var definition = entity().withBusinessCodes(Map.of("documentNo", new CodeFieldDefinition(true, CodeTrigger.EXPLICIT)));
        new ModuleDefinitionValidator().validateEntity(definition);
        assertThat(definition.supports(EntityCapability.BUSINESS_CODE)).isTrue();
        assertThat(definition.withCapabilities(EntityCapability.CRUD).withFormulaRules().withSortPartitionFields()
                .withTenantUniqueConstraints().withFileReferences(Map.of()).businessCodes()).isEqualTo(definition.businessCodes());
        assertThat(definition.withBusinessCodes(Map.of()).supports(EntityCapability.BUSINESS_CODE)).isFalse();
        assertThatThrownBy(() -> definition.businessCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsNonStringUnknownAndMultiplePrimaryTargets() {
        var module = new BusinessCodeCapabilityModule();
        assertThatThrownBy(() -> module.validateDefinition(entity().withBusinessCodes(Map.of("quantity", new CodeFieldDefinition(false, null)))))
                .hasMessageContaining("physical string");
        assertThatThrownBy(() -> module.validateDefinition(entity().withBusinessCodes(Map.of("missing", new CodeFieldDefinition(false, null)))))
                .hasMessageContaining("Unknown business code");
        assertThatThrownBy(() -> entity().withBusinessCodes(Map.of("documentNo", new CodeFieldDefinition(true, null), "quantity", new CodeFieldDefinition(true, null))))
                .hasMessageContaining("Only one PRIMARY");
    }
    @Test void staticModelCompilesTheSameRoleAndTriggerFacts() {
        var definition = new StaticEntityDefinitionCompiler().compile("invoice", "Invoice", Invoice.class);
        assertThat(definition.businessCodes()).containsEntry("documentNo", new CodeFieldDefinition(true, CodeTrigger.EXPLICIT));
        assertThat(definition.supports(EntityCapability.BUSINESS_CODE)).isTrue();
    }
    @Table(name = "static_coded_invoice")
    static class Invoice {
        @BusinessCode(primary = true, trigger = CodeTrigger.EXPLICIT)
        @Column(name = "document_no", type = ColumnType.VARCHAR, length = 32) String documentNo;
    }
}
