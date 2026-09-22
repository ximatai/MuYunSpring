package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.security.AssistantFieldPolicy;
import net.ximatai.muyun.spring.common.security.EncryptedField;
import net.ximatai.muyun.spring.common.security.MaskedField;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantFieldPolicyProjectionTest {
    @Test
    void preservesExplicitPoliciesAcrossCompilationAndFormulaUiStateProjection() {
        var definition = ModuleUiDefinition.builder("demo.policy")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("value", field -> field.assistantPolicy(AssistantFieldPolicy.READ))))
                        .detail(detail -> detail.editor(editor -> editor
                                .field("value", field -> field.assistantPolicy(AssistantFieldPolicy.DESCRIBE))
                                .field("normal")))))
                .build();
        var descriptor = ModuleUiDescriptorCompiler.compile(definition);
        assertThat(descriptor.page().list().fields().fields().getFirst().assistantPolicy()).isEqualTo(AssistantFieldPolicy.READ);
        var field = descriptor.page().detail().editor().fields().getFirst();
        assertThat(field.withReadOnly(UiRule.constant(true)).assistantPolicy()).isEqualTo(AssistantFieldPolicy.DESCRIBE);
        assertThat(field.withUiState(UiRule.constant(false), UiRule.constant(true)).assistantPolicy()).isEqualTo(AssistantFieldPolicy.DESCRIBE);
        assertThat(descriptor.page().detail().editor().fields().getLast().assistantPolicy()).isEqualTo(AssistantFieldPolicy.READ_WRITE);
    }

    @Test
    void passwordCannotBeReopenedByAnExplicitPermissivePolicy() {
        assertThat(ViewFieldDefinition.field("password").secretInput().assistantPolicy(AssistantFieldPolicy.READ_WRITE)
                .build().assistantPolicy()).isEqualTo(AssistantFieldPolicy.HIDDEN);
        var field = new ResolvedViewFieldDescriptor(ViewFieldRef.main("password"), null, null, null, null,
                "password", null, null, null, null);
        assertThat(field.assistantPolicy()).isEqualTo(AssistantFieldPolicy.HIDDEN);
        assertThat(field.withAssistantPolicy(AssistantFieldPolicy.READ_WRITE).assistantPolicy()).isEqualTo(AssistantFieldPolicy.HIDDEN);
    }

    @Test
    void staticAndDynamicProtectionCeilingsCoverListsAndEditorsAndDoNotReopenHiddenFields() {
        var definition = ModuleUiDefinition.builder("demo.policy")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("secret").field("phone")))
                        .detail(detail -> detail.editor(editor -> editor.field("secret")
                                .field("phone", field -> field.assistantPolicy(AssistantFieldPolicy.HIDDEN))))))
                .build();
        var original = ModuleUiDescriptorCompiler.compile(definition);
        var staticDescriptor = AssistantFieldPolicyProjection.protectStatic(original,
                StaticModuleDefinition.builder("demo", "demo.policy", "Policy").modelClass(ProtectedModel.class).build());
        var dynamicDescriptor = AssistantFieldPolicyProjection.protect(original,
                Set.of(ViewFieldRef.main("secret"), ViewFieldRef.main("phone")));
        assertThat(staticDescriptor).isEqualTo(dynamicDescriptor);
        assertThat(staticDescriptor.page().list().fields().fields()).allSatisfy(field ->
                assertThat(field.assistantPolicy()).isEqualTo(AssistantFieldPolicy.DESCRIBE));
        assertThat(staticDescriptor.page().detail().editor().fields()).extracting(ResolvedViewFieldDescriptor::assistantPolicy)
                .containsExactly(AssistantFieldPolicy.DESCRIBE, AssistantFieldPolicy.HIDDEN);
    }

    static class ProtectedModel {
        @EncryptedField String secret;
        @MaskedField(FieldMaskingPolicy.PHONE) String phone;
    }
}
