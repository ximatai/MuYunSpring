package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPresetCatalog;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CronFieldControlTest {
    private ModuleUiDefinition definition() {
        return ModuleUiDefinition.builder("demo.schedule")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("Schedule").titleField("expression"))
                        .detail(detail -> detail.editor(form -> form.field("expression", field -> field.uiType("cron")))))).build();
    }
    @Test void staticAndDynamicUseTheSameScalarCronControl() {
        var controls = FieldControlDescriptorCatalog.fromConfigured(FieldUiControlPresetCatalog.fieldUiControls(),
                FieldUiControlPresetCatalog.properties(), FieldUiControlPresetCatalog.bindings());
        var types = Map.of(ViewFieldRef.main("expression"), FieldValueType.STRING);
        var statik = ModuleUiDescriptorCompiler.compile(definition(), ModuleKind.STATIC, "Schedule", Map.of(), Map.of(), null, types);
        var dynamic = ModuleUiDescriptorCompiler.compile(definition(), ModuleKind.DYNAMIC, "Schedule", Map.of(), Map.of(), null, types, controls);
        assertThat(statik.page().detail().editor().fields().getFirst().fieldControl())
                .isEqualTo(dynamic.page().detail().editor().fields().getFirst().fieldControl());
        assertThat(statik.page().detail().editor().fields().getFirst().fieldControl().alias()).isEqualTo("cron");
    }
    @Test void sourceCompatibleDeclarationsMayOmitModelTypes() {
        assertThat(ModuleUiDescriptorCompiler.compile(definition()).page().detail().editor().fields().getFirst()
                .fieldControl().alias()).isEqualTo("cron");
    }
    @Test void rejectsNonTextStorage() {
        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition(), ModuleKind.DYNAMIC, "Schedule", Map.of(), Map.of(), null,
                Map.of(ViewFieldRef.main("expression"), FieldValueType.INTEGER)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scalar text storage");
    }
    @Test void schedulePagesExposeOnlyFieldsForTheSelectedScheduleKind() {
        var definitions = java.util.List.of(
                new net.ximatai.muyun.spring.platform.web.job.JobPlanWebController(
                        org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.job.JobService.class)).moduleUiDefinition(),
                new net.ximatai.muyun.spring.platform.web.job.ScheduledActionPlanWebController(
                        org.mockito.Mockito.mock(net.ximatai.muyun.spring.common.i18n.TextResolver.class)).moduleUiDefinition());
        for (var definition : definitions) {
            var fields = ModuleUiDescriptorCompiler.compile(definition).page().detail().editor().fields();
            for (var name : java.util.List.of("expression", "zoneId", "intervalMillis", "initialDelayMillis")) {
                String kind = name.equals("expression") || name.equals("zoneId") ? "CRON" : "FIXED_DELAY";
                assertThat(fields).filteredOn(field -> field.fieldRef().fieldName().equals(name))
                        .singleElement().satisfies(field -> assertThat(field.visible().formula().expression())
                                .isEqualTo("{scheduleKind} == '" + kind + "'"));
            }
        }
    }
}
