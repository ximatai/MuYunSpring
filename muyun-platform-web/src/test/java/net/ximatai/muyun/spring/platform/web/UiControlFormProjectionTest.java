package net.ximatai.muyun.spring.platform.web;
import net.ximatai.muyun.spring.platform.ui.UiControlRule;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class UiControlFormProjectionTest {
    private ResolvedViewDescriptor form(boolean readOnly) {
        return new ResolvedViewDescriptor("edit", ModuleViewKind.FORM, ModuleUiClientType.WEB, "编辑", List.of(
                new ResolvedViewFieldDescriptor(ViewFieldRef.main("quantity"), "数量", UiRule.constant(true),
                        UiRule.constant(false), UiRule.constant(readOnly), null, null, null, null)));
    }
    private UiControlRule rule(String formKey, boolean enabled) {
        return new UiControlRule("uiControl1", formKey, "PRESENT({quantity})", enabled,
                List.of(new UiControlRule.Target("quantity", true, true)));
    }
    @Test void projectsOnlyOntoChosenFormAndRetainsExistingRestrictions() {
        var source = form(true);
        var projected = UiControlFormProjection.projectView("default", source, List.of(rule("default", true)));
        var field = projected.fields().getFirst();
        assertThat(field.visible().formula().expression()).isEqualTo("!(PRESENT({quantity}))");
        assertThat(field.readOnly().constant()).isTrue();
        assertThat(field.visible().formula().program().referencedFields()).containsExactly("quantity");
        assertThat(UiControlFormProjection.projectView("detail", source, List.of(rule("default", true)))).isEqualTo(source);
        assertThat(UiControlFormProjection.projectView("default", source, List.of(rule("default", false)))).isEqualTo(source);
    }
    @Test void validatesFormElementAndPortablePredicate() {
        var forms = List.of(new UiControlFormProjection.Form("default", "编辑", List.of(new UiControlFormProjection.Element("quantity", "数量"))));
        UiControlGovernanceService.validate(List.of(rule("default", true)), forms);
        assertThatThrownBy(() -> UiControlGovernanceService.validate(List.of(rule("missing", true)), forms)).hasMessageContaining("有效的表单");
        assertThatThrownBy(() -> UiControlGovernanceService.validate(List.of(new UiControlRule("ui", "default", "PRESENT({secret})", true,
                List.of(new UiControlRule.Target("quantity", true, false)))), forms)).hasMessageContaining("参与字段");
        assertThatThrownBy(() -> UiControlGovernanceService.validate(List.of(new UiControlRule("ui", "default", "PRESENT({quantity})", true,
                List.of(new UiControlRule.Target("quantity", false, false)))), forms)).hasMessageContaining("隐藏或只读");
    }
}
