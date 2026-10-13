package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewFieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicModuleRegistryTest {
    @Test
    void shouldRegisterAndResolveDynamicModuleDefinitions() {
        DynamicModuleRegistry registry = new DynamicModuleRegistry();
        ModuleDefinition module = contractModule();

        registry.register(module);

        assertThat(registry.modules()).containsExactly(module);
        assertThat(registry.findModule("sales.contract")).contains(module);
        assertThat(registry.requireEntity("sales.contract", "contract"))
                .isEqualTo(module.entities().getFirst());
    }

    @Test
    void shouldRejectDuplicateOrUnknownDefinitions() {
        DynamicModuleRegistry registry = new DynamicModuleRegistry();
        registry.register(contractModule());

        assertThatThrownBy(() -> registry.register(contractModule()))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("duplicate module alias");
        assertThatThrownBy(() -> registry.requireModule("sales.order"))
                .isInstanceOf(ModuleDefinitionException.class)
                .isInstanceOf(net.ximatai.muyun.spring.dynamic.metadata.UnknownDynamicModuleException.class)
                .hasMessageContaining("unknown module alias");
        assertThatThrownBy(() -> registry.requireEntity("sales.contract", "missing"))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown entity");
    }

    @Test
    void shouldRejectInvalidViewDefinitions() {
        ModuleDefinition invalid = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(contractModule().entities())
                .relations(List.of())
                .references(List.of())
                .views(List.of(new EntityViewDefinition(
                        "contract",
                        EntityViewType.LIST,
                        "Contract list",
                        List.of(new EntityViewFieldDefinition("missingField"))
                )))
                .build();

        assertThatThrownBy(() -> new DynamicModuleRegistry().register(invalid))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown view field");
    }

    private ModuleDefinition contractModule() {
        return new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(new EntityDefinition(
                        "contract",
                        "app_contract",
                        "Contract",
                        List.of(
                                FieldDefinition.string("code", "Code").length(64).required(),
                                FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                        )
                ))
        );
    }
}
