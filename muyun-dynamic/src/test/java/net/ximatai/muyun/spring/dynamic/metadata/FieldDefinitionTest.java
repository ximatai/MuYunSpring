package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.security.FieldSignatureMode;
import net.ximatai.muyun.spring.dynamic.schema.DynamicTableMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldDefinitionTest {
    @Test
    void shouldExposeDictionaryBindingAsUnifiedOptionBinding() {
        FieldDefinition field = FieldDefinition.string("status", "Status")
                .dictionary("crm", "customer_status");

        assertThat(field.dictionaryBinding())
                .isEqualTo(new FieldDictionaryBinding("crm", "customer_status"));
        assertThat(field.dictionaryBinding().selectionMode()).isEqualTo(OptionSelectionMode.SINGLE);
        assertThat(field.optionBinding())
                .isEqualTo(OptionBinding.dictionary("crm", "customer_status"));
    }

    @Test
    void shouldExposeMultipleDictionarySelectionMode() {
        FieldDefinition field = FieldDefinition.of("tags", FieldType.JSON, "Tags")
                .dictionary("crm", "customer_tag", OptionSelectionMode.MULTIPLE);

        assertThat(field.dictionaryBinding().selectionMode()).isEqualTo(OptionSelectionMode.MULTIPLE);
    }

    @Test
    void shouldExposeQueryDefinitionOnField() {
        FieldDefinition title = FieldDefinition.titleField().queryable();
        FieldDefinition status = FieldDefinition.string("status", "Status")
                .queryable(DynamicQueryOperator.EQ, Set.of(DynamicQueryOperator.EQ, DynamicQueryOperator.IN));

        assertThat(title.queryDefinition().queryable()).isTrue();
        assertThat(title.queryDefinition().defaultOperator()).isEqualTo(DynamicQueryOperator.LIKE);
        assertThat(status.queryDefinition().operators()).containsExactlyInAnyOrder(DynamicQueryOperator.EQ, DynamicQueryOperator.IN);
    }

    @Test
    void shouldExposeFieldBehaviorDefinition() {
        FieldDefinition field = FieldDefinition.string("code", "Code")
                .defaultValue("AUTO")
                .validationRegex("[A-Z]+")
                .notCopyable()
                .writeProtected();

        assertThat(field.behavior().defaultValue()).isEqualTo("AUTO");
        assertThat(field.behavior().validationRegex()).isEqualTo("[A-Z]+");
        assertThat(field.behavior().copyable()).isFalse();
        assertThat(field.behavior().writeProtected()).isTrue();
    }

    @Test
    void shouldExposeVirtualFieldStorageFormAndPreserveAcrossFluentMethods() {
        FieldDefinition field = FieldDefinition.string("displayCode", "Display Code")
                .virtual()
                .column("display_code")
                .defaultUiType("text")
                .notCopyable()
                .writeProtected();

        assertThat(field.storageForm()).isEqualTo(FieldStorageForm.VIRTUAL);
        assertThat(field.isPhysical()).isFalse();
        assertThat(field.columnName()).isEqualTo("display_code");
        assertThat(field.defaultUiControlAlias()).isEqualTo("text");
        assertThat(field.behavior().copyable()).isFalse();
        assertThat(field.behavior().writeProtected()).isTrue();
    }

    @Test
    void shouldPreserveOptionLoadAcrossFluentMethods() {
        FieldDefinition field = FieldDefinition.string("subjectTitle", "Subject Title")
                .virtual()
                .optionLoad("subjectCode")
                .defaultUiType("text")
                .column("subject_title");

        assertThat(field.optionLoad()).isEqualTo(new FieldOptionLoadDefinition("subjectCode"));
    }

    @Test
    void shouldExposeJsonSetValueShapeAndPreserveAcrossFluentMethods() {
        FieldDefinition field = FieldDefinition.of("tags", FieldType.JSON, "Tags")
                .jsonSet()
                .column("tag_values")
                .queryable(DynamicQueryOperator.CONTAINS, Set.of(DynamicQueryOperator.CONTAINS))
                .defaultUiType("multi_select");

        assertThat(field.valueShape()).isEqualTo(FieldValueShape.JSON_SET);
        assertThat(field.columnName()).isEqualTo("tag_values");
        assertThat(field.queryDefinition().operators()).containsExactly(DynamicQueryOperator.CONTAINS);
        assertThat(field.defaultUiControlAlias()).isEqualTo("multi_select");
    }

    @Test
    void shouldValidateJsonSetValueShapeContract() {
        ModuleDefinitionValidator validator = new ModuleDefinitionValidator();

        validator.validateEntity(new EntityDefinition(
                "customer",
                "crm_customer",
                "Customer",
                java.util.List.of(FieldDefinition.of("tags", FieldType.JSON, "Tags")
                        .jsonSet()
                        .queryable(DynamicQueryOperator.CONTAINS, Set.of(DynamicQueryOperator.CONTAINS)))
        ));

        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "customer",
                "crm_customer",
                "Customer",
                java.util.List.of(FieldDefinition.string("tags", "Tags").jsonSet())
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("JSON_SET value shape requires JSON field");
        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "customer",
                "crm_customer",
                "Customer",
                java.util.List.of(FieldDefinition.of("tags", FieldType.JSON, "Tags")
                        .queryable(DynamicQueryOperator.CONTAINS, Set.of(DynamicQueryOperator.CONTAINS)))
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("collection query operators require JSON_SET value shape");
    }

    @Test
    void shouldRejectQueryOperatorUnsupportedByFieldType() {
        assertThatThrownBy(() -> FieldDefinition.decimal("amount", "Amount")
                .queryable(DynamicQueryOperator.LIKE, Set.of(DynamicQueryOperator.LIKE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void shouldRejectInvalidFieldBehaviorDefinition() {
        ModuleDefinitionValidator validator = new ModuleDefinitionValidator();

        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                java.util.List.of(FieldDefinition.string("code", "Code")
                        .defaultValue("abc")
                        .validationRegex("[A-Z]+"))
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("defaultValue");
        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                java.util.List.of(FieldDefinition.bool("enabled", "Enabled").defaultValue("abc"))
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("boolean defaultValue");
    }

    @Test
    void shouldRejectVirtualFieldStorageDependentBehavior() {
        ModuleDefinitionValidator validator = new ModuleDefinitionValidator();

        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                java.util.List.of(FieldDefinition.string("displayCode", "Display Code").column("display_code")
                        .virtual().queryable())
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("virtual field cannot be queryable");
        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                java.util.List.of(FieldDefinition.string("displayCode", "Display Code").column("display_code")
                        .virtual().indexed())
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("virtual field cannot be required, unique, indexed, sortable or title field");
        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                java.util.List.of(FieldDefinition.string("displayCode", "Display Code").column("display_code")
                        .virtual().defaultValue("AUTO"))
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("virtual field cannot define default value or validation regex");
    }

    @Test
    void shouldRejectWriteRulesOnVirtualFields() {
        EntityDefinition entity = new EntityDefinition("customer", "customer", "Customer",
                java.util.List.of(FieldDefinition.string("displayCode", "Display Code").column("display_code").virtual()
                        .writeRules(new FieldWriteRules(true, false,
                                TextNormalization.TRIM))));
        assertThatThrownBy(() -> new ModuleDefinitionValidator().validateEntity(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("virtual field cannot define write rules");
    }

    @Test
    void shouldExcludeVirtualFieldsFromDynamicTableMapping() {
        EntityDefinition entity = new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                java.util.List.of(
                        FieldDefinition.string("code", "Code").column("code"),
                        FieldDefinition.string("displayCode", "Display Code").column("display_code").virtual()
                )
        );

        assertThat(new DynamicTableMapper().toTable(entity).getColumns())
                .extracting(net.ximatai.muyun.database.core.builder.Column::getName)
                .contains("code")
                .doesNotContain("display_code");
    }

    @Test
    void shouldRequireJsonFieldForMultipleDictionaryBinding() {
        ModuleDefinitionValidator validator = new ModuleDefinitionValidator();

        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "customer",
                "crm_customer",
                "Customer",
                java.util.List.of(FieldDefinition.string("tags", "Tags")
                        .dictionary("crm", "customer_tag", OptionSelectionMode.MULTIPLE))
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("multiple dictionary binding requires JSON field");
    }

    @Test
    void shouldValidateDictionaryOptionLoadOnVirtualOutputField() {
        ModuleDefinitionValidator validator = new ModuleDefinitionValidator();
        EntityDefinition entity = new EntityDefinition("teacher", "edu_teacher", "Teacher", java.util.List.of(
                FieldDefinition.string("subjectCode", "Subject")
                        .column("subject_code").dictionary("education", "teaching_subject"),
                FieldDefinition.string("subjectTitle", "Subject Title")
                        .column("subject_title").virtual().optionLoad("subjectCode")
        ));

        validator.validateEntity(entity);

        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition("teacher", "edu_teacher", "Teacher",
                java.util.List.of(FieldDefinition.string("subjectCode", "Subject").column("subject_code"),
                        FieldDefinition.string("subjectTitle", "Subject Title").column("subject_title")
                                .virtual().optionLoad("subjectCode")))))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("option load source requires dictionary binding");
        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition("teacher", "edu_teacher", "Teacher",
                java.util.List.of(FieldDefinition.string("subjectCode", "Subject")
                                .column("subject_code").dictionary("education", "teaching_subject"),
                        FieldDefinition.string("subjectTitle", "Subject Title").column("subject_title")
                                .optionLoad("subjectCode")))))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("option load requires virtual output field");
    }

    @Test
    void shouldValidateZonedTimestampCompanionFieldContract() {
        ModuleDefinitionValidator validator = new ModuleDefinitionValidator();

        validator.validateEntity(new EntityDefinition(
                "meeting",
                "app_meeting",
                "Meeting",
                java.util.List.of(
                        FieldDefinition.zonedTimestamp("meetingAt", "Meeting At").column("meeting_at").required(),
                        FieldDefinition.zonedTimestampTimeZone("meetingAt", "meeting_at").required()
                )
        ));

        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "meeting",
                "app_meeting",
                "Meeting",
                java.util.List.of(FieldDefinition.zonedTimestamp("meetingAt", "Meeting At").column("meeting_at"))
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("field companion is missing");
        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "meeting",
                "app_meeting",
                "Meeting",
                java.util.List.of(
                        FieldDefinition.zonedTimestamp("meetingAt", "Meeting At").column("meeting_at"),
                        FieldDefinition.integer("meetingAtTimeZone", "Time Zone").column("meeting_at_timezone")
                )
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("field companion type mismatch");
        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "meeting",
                "app_meeting",
                "Meeting",
                java.util.List.of(
                        FieldDefinition.zonedTimestamp("meetingAt", "Meeting At").column("meeting_at"),
                        FieldDefinition.string("meetingAtTimeZone", "Time Zone").column("time_zone")
                )
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("field companion column mismatch");
        assertThatThrownBy(() -> validator.validateEntity(new EntityDefinition(
                "meeting",
                "app_meeting",
                "Meeting",
                java.util.List.of(
                        FieldDefinition.zonedTimestamp("meetingAt", "Meeting At").column("meeting_at").required(),
                        FieldDefinition.zonedTimestampTimeZone("meetingAt", "meeting_at")
                )
        )))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("required field requires required companion");
    }

    @Test
    void shouldComposeGeneratedSignatureCompanionWithDeclaredTimeZoneCompanion() {
        EntityDefinition entity = new EntityDefinition(
                "meeting",
                "app_meeting",
                "Meeting",
                java.util.List.of(
                        FieldDefinition.zonedTimestamp("meetingAt", "Meeting At")
                                .column("meeting_at")
                                .protection(new FieldProtectionDefinition(
                                        FieldEncryptionMode.NONE,
                                        FieldSignatureMode.SIGNED,
                                        FieldMaskingPolicy.NONE
                                )),
                        FieldDefinition.zonedTimestampTimeZone("meetingAt", "meeting_at")
                )
        );

        new ModuleDefinitionValidator().validateEntity(entity);

        assertThat(FieldCompanionRules.recordFields(entity))
                .extracting(FieldDefinition::fieldName)
                .contains("meetingAt", "meetingAtTimeZone", "meetingAtSignature");
        assertThat(FieldCompanionRules.recordFields(entity))
                .filteredOn(field -> field.fieldName().equals("meetingAtSignature"))
                .singleElement()
                .satisfies(field -> assertThat(field.columnName()).isEqualTo("meeting_at_signature"));
    }

    @Test
    void shouldRejectQueryableProtectedStorageField() {
        FieldDefinition secret = FieldDefinition.string("secret", "Secret")
                .protection(new FieldProtectionDefinition(
                        FieldEncryptionMode.ENCRYPTED,
                        FieldSignatureMode.NONE,
                        FieldMaskingPolicy.NONE
                ))
                .queryable();
        EntityDefinition entity = new EntityDefinition("contract", "app_contract", "Contract",
                java.util.List.of(secret));

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validateEntity(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("protected storage field cannot be queryable");
    }
}
