package net.ximatai.muyun.spring.ability.form;

import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.DictionaryField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormSchemaTest {
    @Test
    void shouldRetainModelOperationRequirementsAcrossOptionAndFormProjection() {
        var descriptor = FormDescriptor.builder("demo.form")
                .field(FormField.of("code").withOptionBinding(OptionBinding.dictionary("demo", "code")))
                .build();
        var schema = FormSchema.from(descriptor, RequiredRecord.class);
        assertThat(schema.fields().getFirst().required()).isFalse();
        assertThat(schema.fields().getFirst().inputRequirements()).isEqualTo(
                new net.ximatai.muyun.spring.common.model.constraint.FieldInputRequirements(true, false));
        var explicit = FormField.of("code").withInputRequirements(
                net.ximatai.muyun.spring.common.model.constraint.FieldInputRequirements.NONE)
                .withTitle("编码").withOptionBinding(OptionBinding.dictionary("demo", "code"));
        assertThat(FormSchema.from(FormDescriptor.builder("demo.form").field(explicit).build(), RequiredRecord.class)
                .fields().getFirst().inputRequirements()).isEqualTo(
                        net.ximatai.muyun.spring.common.model.constraint.FieldInputRequirements.NONE);
    }

    private static class RequiredRecord {
        @net.ximatai.muyun.spring.common.model.constraint.Required(
                on = net.ximatai.muyun.spring.common.model.constraint.WriteOperation.INSERT)
        private String code;
    }

    @Test
    void shouldExposeDescriptorAsFrontendConsumableSchema() {
        FormDescriptor descriptor = FormDescriptor.builder("iam.employee")
                .title("职员档案")
                .field(FormField.of("employeeNo").withTitle("职员编号").asRequired())
                .field(FormField.of("enabled", FormValueType.BOOLEAN).withTitle("启用状态"))
                .build();

        FormSchema schema = FormSchema.from(descriptor);

        assertThat(schema.scopeName()).isEqualTo("iam.employee");
        assertThat(schema.title()).isEqualTo("职员档案");
        assertThat(schema.fields()).hasSize(2);
        assertThat(schema.fields().getFirst()).satisfies(field -> {
            assertThat(field.name()).isEqualTo("employeeNo");
            assertThat(field.title()).isEqualTo("职员编号");
            assertThat(field.valueType()).isEqualTo(FormValueType.STRING);
            assertThat(field.controlType()).isEqualTo(FormControlType.TEXT);
            assertThat(field.required()).isTrue();
        });
        assertThat(schema.fields().get(1)).satisfies(field -> {
            assertThat(field.name()).isEqualTo("enabled");
            assertThat(field.controlType()).isEqualTo(FormControlType.SWITCH);
        });
    }

    @Test
    void shouldMergeStaticOptionFieldMetadataIntoFormSchema() {
        FormDescriptor descriptor = FormDescriptor.builder("iam.employee")
                .field(FormField.of("gender").withTitle("性别"))
                .build();

        FormSchema schema = FormSchema.from(descriptor, EmployeeOptionRecord.class);

        assertThat(schema.fields()).singleElement().satisfies(field -> {
            assertThat(field.name()).isEqualTo("gender");
            assertThat(field.optionBinding()).isEqualTo(OptionBinding.dictionary("iam", "gender"));
            assertThat(field.controlType()).isEqualTo(FormControlType.SELECT);
            assertThat(field.optionTitleField()).isEqualTo("genderTitle");
        });
    }

    @Test
    void shouldKeepExplicitFormOptionBindingWhenStaticOptionFieldAlsoExists() {
        FormDescriptor descriptor = FormDescriptor.builder("iam.employee")
                .field(FormField.of("gender")
                        .withOptionBinding(OptionBinding.dictionary("crm", "gender")))
                .build();

        FormSchema schema = FormSchema.from(descriptor, EmployeeOptionRecord.class);

        assertThat(schema.fields()).singleElement().satisfies(field -> {
            assertThat(field.optionBinding()).isEqualTo(OptionBinding.dictionary("crm", "gender"));
            assertThat(field.optionTitleField()).isEqualTo("genderTitle");
        });
    }

    private static class EmployeeOptionRecord {
        @DictionaryField(source = "iam.gender")
        private String gender;

        @OptionLoad(source = "gender")
        private String genderTitle;
    }
}
