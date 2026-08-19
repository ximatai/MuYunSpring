package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityStandardActionCatalog;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityModuleRegistryTest {
    private final CapabilityModuleRegistry registry = CapabilityModuleRegistry.defaultRegistry();

    private EnableCapabilityModule enable() {
        return registry.require(EntityCapability.ENABLE, EnableCapabilityModule.class);
    }

    @Test
    void shouldOwnEnableDefinitionAndStandardActionsAsOneTypedModule() {
        EntityDefinition entity = entity(Set.of(EntityCapability.CRUD, EntityCapability.ENABLE), FieldDefinition.enabled());

        registry.validate(entity);

        assertThat(enable().actions().standardActions())
                .containsExactly(PlatformAction.ENABLE, PlatformAction.DISABLE);
        assertThat(registry.find(EntityCapability.ENABLE)).containsSame(enable());
        assertThat(registry.actionOwner(PlatformAction.ENABLE)).containsSame(enable().actions());
        assertThat(registry.actionOwner(PlatformAction.ENABLE).orElseThrow()
                .endpointProjection(PlatformAction.ENABLE).orElseThrow().path()).isEqualTo("/enable/{id}");
        assertThat(registry.actionOwner(PlatformAction.DISABLE).orElseThrow()
                .endpointProjection(PlatformAction.DISABLE).orElseThrow().path()).isEqualTo("/disable/{id}");
    }

    @Test
    void shouldRejectEnableWithoutItsOwnedDynamicField() {
        EntityDefinition entity = entity(Set.of(EntityCapability.CRUD, EntityCapability.ENABLE), FieldDefinition.string("code", "Code"));

        assertThatThrownBy(() -> registry.validate(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("ENABLE capability requires standard field enabled");
    }

    @Test
    void shouldPublishDependencyAndRejectDuplicateModuleRegistration() {
        assertThat(enable().dependencies()).containsExactly(EntityCapability.CRUD);
        assertThatIllegalStateException().isThrownBy(() -> new CapabilityModuleRegistry(List.of(
                new EnableCapabilityModule(), new EnableCapabilityModule())))
                .withMessageContaining("duplicate capability module registration");
    }

    @Test
    void shouldNotContributeOrDispatchEnableActionsWhenCapabilityIsAbsent() {
        EntityDefinition entity = entity(Set.of(EntityCapability.CRUD), FieldDefinition.string("code", "Code"));

        assertThat(EntityStandardActionCatalog.from(entity))
                .extracting(action -> action.actionCode())
                .doesNotContain(PlatformAction.ENABLE.code(), PlatformAction.DISABLE.code());
        assertThat(registry.actionOwner(PlatformAction.CREATE)).isEmpty();
        assertThat(enable().actions().endpointProjection(PlatformAction.CREATE)).isEmpty();
    }

    private EntityDefinition entity(Set<EntityCapability> capabilities, FieldDefinition field) {
        return new EntityDefinition("contract", "contract", "Contract", List.of(field), capabilities);
    }
}
