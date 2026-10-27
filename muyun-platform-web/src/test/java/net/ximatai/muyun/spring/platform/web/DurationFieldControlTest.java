package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPresetCatalog;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DurationFieldControlTest {
    private ModuleUiDefinition definition() {
        return ModuleUiDefinition.builder("demo.expiry")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("Expiry").titleField("age")).detail(detail -> detail.editor(form -> form
                        .field("age", field -> field.uiType("duration_seconds")))))).build();
    }

    @Test
    void staticAndConfiguredDynamicControlsDeliverTheSameIntegerSecondsContract() {
        var types = Map.of(ViewFieldRef.main("age"), FieldValueType.INTEGER);
        var controls = FieldControlDescriptorCatalog.fromConfigured(FieldUiControlPresetCatalog.fieldUiControls(),
                FieldUiControlPresetCatalog.properties(), FieldUiControlPresetCatalog.bindings());
        var staticField = ModuleUiDescriptorCompiler.compile(definition(), ModuleKind.STATIC, "Expiry",
                Map.of(), Map.of(), null, types).page().detail().editor().fields().getFirst();
        var dynamicField = ModuleUiDescriptorCompiler.compile(definition(), ModuleKind.DYNAMIC, "Expiry",
                Map.of(), Map.of(), null, types, controls).page().detail().editor().fields().getFirst();
        assertThat(staticField.fieldControl()).isEqualTo(dynamicField.fieldControl());
        assertThat(staticField.fieldControl().alias()).isEqualTo("duration_seconds");
        assertThat(staticField.valueType()).isEqualTo(FieldValueType.INTEGER);
    }

    @Test
    void rejectsIncompatibleStorageRatherThanRoundingOrReinterpretingIt() {
        for (var type : new FieldValueType[]{FieldValueType.STRING, FieldValueType.LONG, FieldValueType.DECIMAL}) {
            assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition(), ModuleKind.DYNAMIC, "Expiry",
                    Map.of(), Map.of(), null, Map.of(ViewFieldRef.main("age"), type)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INTEGER seconds storage");
        }
    }
}
