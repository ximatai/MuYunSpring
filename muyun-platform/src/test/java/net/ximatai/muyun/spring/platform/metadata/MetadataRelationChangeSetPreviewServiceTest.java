package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicSchemaGovernanceFacts;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryFieldValueValidator;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.nullable;

class MetadataRelationChangeSetPreviewServiceTest {
    @Test
    void shouldRejectDefaultsOutsideTheInheritedPhysicalShapeBeforePublication() {
        MetadataField existing = businessField("code", "code", "string"); existing.setVersion(2);
        Fixture fixture = fixture(RelationRole.MAIN, List.of(existing));
        FieldSpec spec = new FieldSpec(); spec.setFieldType(net.ximatai.muyun.spring.dynamic.metadata.FieldType.STRING);
        spec.setDefaultLength(128);
        when(fixture.fieldSpecService.requireFieldType("string")).thenReturn(spec);
        MetadataFieldConfig base = new MetadataFieldConfig(); base.setVersion(5); base.setFieldLength(3);
        when(fixture.fieldConfigService.findByMetadataFieldId("field-0")).thenReturn(base);
        var result = fixture.service.preview("crm.customer", "main", command(3, Map.of(), List.of(
                new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE, "field-0", 2, existing,
                        new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC, null, null, null,
                                new MetadataFieldFixedDefaultDraft("ABCD", 5))))));
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIXED_DEFAULT");
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::message).anyMatch(message -> message.contains("field length"));
    }

    @Test
    void shouldValidateDictionaryDefaultCodesAndSelectionShapeBeforePublication() {
        for (var mode : net.ximatai.muyun.spring.common.option.OptionSelectionMode.values()) {
            Fixture fixture = fixture(RelationRole.MAIN, List.of());
            boolean multiple = mode == net.ximatai.muyun.spring.common.option.OptionSelectionMode.MULTIPLE;
            FieldSpec spec = new FieldSpec(); spec.setFieldType(multiple
                    ? net.ximatai.muyun.spring.dynamic.metadata.FieldType.JSON
                    : net.ximatai.muyun.spring.dynamic.metadata.FieldType.STRING);
            when(fixture.fieldSpecService.requireFieldType("status")).thenReturn(spec);
            when(fixture.dictionaryItems.resolveEnabledItem("crm", "state", "NEW"))
                    .thenReturn(new net.ximatai.muyun.spring.platform.dictionary.DictionaryItem());
            MetadataFieldConfig dictionary = new MetadataFieldConfig(); dictionary.setDictionaryApplicationAlias("crm");
            dictionary.setDictionaryCategoryAlias("state"); dictionary.setSelectionMode(mode);
            java.util.function.Function<String, MetadataRelationChangeSetPreview> preview = value -> fixture.service.preview(
                    "crm.customer", "main", command(3, Map.of(), List.of(new MetadataFieldChangeSetDraft(
                            MetadataFieldChangeSetDraft.Operation.ADD, null, null, businessField("status", "status", "status"),
                            new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.DICTIONARY, null, null, dictionary,
                                    new MetadataFieldFixedDefaultDraft(value, null))))));
            assertThat(preview.apply(multiple ? "[\"NEW\"]" : "NEW").errors()).isEmpty();
            assertThat(preview.apply(multiple ? "[\"UNKNOWN\"]" : "UNKNOWN").errors())
                    .extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIXED_DEFAULT");
            if (multiple) {
                for (String invalid : List.of("NEW", "\"NEW\"", "{\"code\":\"NEW\"}", "[\"NEW\",\"NEW\"]", "[1]")) {
                    assertThat(preview.apply(invalid).errors()).extracting(MetadataChangeSetValidationIssue::code)
                            .contains("INVALID_FIXED_DEFAULT");
                }
            }
        }
    }
    @Test
    void shouldValidateDefaultUsingTheSameInheritedNormalizationAsRuntime() {
        MetadataField existing = businessField("code", "code", "string"); existing.setVersion(2);
        Fixture fixture = fixture(RelationRole.MAIN, List.of(existing));
        var string = new FieldSpec(); string.setFieldType(net.ximatai.muyun.spring.dynamic.metadata.FieldType.STRING);
        when(fixture.fieldSpecService.requireFieldType("string")).thenReturn(string);
        var base = new MetadataFieldConfig(); base.setVersion(5); base.setValidationRegex("[A-Z]+");
        base.setTextNormalization(net.ximatai.muyun.spring.common.model.constraint.TextNormalization.TRIM);
        when(fixture.fieldConfigService.findByMetadataFieldId("field-0")).thenReturn(base);
        var result = fixture.service.preview("crm.customer", "main", command(3, Map.of(), List.of(
                new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE, "field-0", 2, existing,
                        new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC, null, null, null,
                                new MetadataFieldFixedDefaultDraft(" ABC ", 5))))));
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void shouldValidateFixedDefaultsBeforePublicationAndBindTheValueToConfirmation() {
        Fixture fixture = fixture(RelationRole.MAIN, List.of());
        FieldSpec decimal = new FieldSpec();
        decimal.setFieldType(net.ximatai.muyun.spring.dynamic.metadata.FieldType.DECIMAL);
        when(fixture.fieldSpecService.requireFieldType("decimal")).thenReturn(decimal);
        MetadataField field = businessField("rate", "rate", "decimal");
        java.util.function.Function<String, MetadataRelationChangeSetPreview> preview = value -> fixture.service.preview(
                "crm.customer", "main", command(3, Map.of(), List.of(new MetadataFieldChangeSetDraft(
                        MetadataFieldChangeSetDraft.Operation.ADD, null, null, field,
                        new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC, null, null, null,
                                new MetadataFieldFixedDefaultDraft(value, null))))));
        var one = preview.apply("1");
        assertThat(one.errors()).isEmpty();
        assertThat(one.plan().fieldMutations().getFirst().property().fixedDefault().value()).isEqualTo("1");
        assertThat(preview.apply("0.9").proposalFingerprint()).isNotEqualTo(one.proposalFingerprint());
        assertThat(preview.apply("not a number").errors()).extracting(MetadataChangeSetValidationIssue::code)
                .contains("INVALID_FIXED_DEFAULT");
        verify(fixture.fieldConfigService, never()).insert(any());
    }

    @Test
    void shouldRejectStaleDefaultsAndUnsupportedRemovalOfInheritedOrLegacyValues() {
        MetadataField existing = businessField("rate", "rate", "decimal");
        existing.setVersion(2);
        Fixture fixture = fixture(RelationRole.MAIN, List.of(existing));
        FieldSpec decimal = new FieldSpec();
        decimal.setFieldType(net.ximatai.muyun.spring.dynamic.metadata.FieldType.DECIMAL);
        when(fixture.fieldSpecService.requireFieldType("decimal")).thenReturn(decimal);
        MetadataFieldConfig base = new MetadataFieldConfig();
        base.setVersion(5);
        base.setDefaultValue("1");
        when(fixture.fieldConfigService.findByMetadataFieldId("field-0")).thenReturn(base);
        for (MetadataFieldFixedDefaultDraft value : List.of(new MetadataFieldFixedDefaultDraft("0.9", 4),
                new MetadataFieldFixedDefaultDraft(null, 5))) {
            var result = fixture.service.preview("crm.customer", "main", command(3, Map.of(), List.of(
                    new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE, "field-0", 2, existing,
                            new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC, null, null, null, value)))));
            assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIXED_DEFAULT");
        }
        ModuleMetadataField legacy = new ModuleMetadataField();
        legacy.setDefaultValue("0.8");
        when(fixture.moduleFieldService.findByRelationAndField("main", "field-0")).thenReturn(legacy);
        var result = fixture.service.preview("crm.customer", "main", command(3, Map.of(), List.of(
                new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE, "field-0", 2, existing,
                        new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC, null, null, null,
                                new MetadataFieldFixedDefaultDraft("0.9", 5))))));
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::message).anyMatch(value -> value.contains("旧模块字段"));
    }
    @Test
    void shouldRejectCapabilityOwnedBusinessFieldsBeforePublicationEvenWithoutDeclarations() {
        for (MetadataField field : List.of(businessField("enabled", "enabled", "boolean"),
                businessField("parentId", "parent_id", "string"),
                businessField("ranking", "sort_order", "integer"))) {
            Fixture fixture = fixture(RelationRole.MAIN, List.of());
            fixture.metadataService.select("metadata-1").setCapabilityDeclarations(java.util.Set.of());
            for (Map<EntityCapability, Boolean> selections : List.of(Map.<EntityCapability, Boolean>of(),
                    Map.of(EntityCapability.ENABLE, true))) {
                var result = fixture.service.preview("crm.customer", "main", command(3, selections,
                        List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, field))));
                assertThat(result.valid()).isFalse();
                assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code)
                        .contains("CAPABILITY_FIELD_CONFLICT");
                assertThat(result.plan().fieldMutations()).isEmpty();
            }
        }
    }

    @Test
    void shouldPreviewFinalAdditiveModelWithoutWritingAnything() {
        Fixture fixture = fixture(RelationRole.MAIN, List.of(businessField("title", "title", "string")));
        MetadataField subject = businessField("subject", "subject", "string");

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(EntityCapability.TREE, true), List.of(new MetadataFieldChangeSetDraft(
                        MetadataFieldChangeSetDraft.Operation.ADD, null, subject))));

        assertThat(result.valid()).isTrue();
        assertThat(result.effectiveCapabilities()).contains(EntityCapability.TREE, EntityCapability.SORT);
        assertThat(result.fieldImpacts()).extracting(MetadataChangeSetFieldImpact::fieldName)
                .contains("subject", "parentId", "sortOrder");
        assertThat(result.schemaImpacts()).extracting(MetadataChangeSetSchemaImpact::columnName)
                .contains("subject", "parent_id", "sort_order");
        assertThat(result.proposalFingerprint()).hasSize(64);
        verify(fixture.metadataService, never()).update(any());
        verify(fixture.fieldService, never()).insert(any());
        verify(fixture.fieldService, never()).delete(anyString());
    }

    @Test
    void shouldReportProtectedDeleteAndCapabilityConflict() {
        MetadataField systemEnabled = businessField("enabled", "enabled", "boolean");
        systemEnabled.setFieldOwnership(MetadataFieldOwnership.STANDARD);
        systemEnabled.setSystemManaged(true);
        Fixture fixture = fixture(RelationRole.MAIN, List.of(systemEnabled));
        MetadataField colliding = businessField("parentId", "business_parent", "string");

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(2,
                Map.of(EntityCapability.TREE, true), List.of(
                        new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.DELETE, "field-enabled", null),
                        new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, colliding))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code)
                .contains("PROTECTED_FIELD", "NON_ADDITIVE_FIELD_DELETE", "CAPABILITY_FIELD_CONFLICT");
    }

    @Test
    void shouldReportStaleVersionAndChildCapabilitySelection() {
        Fixture fixture = fixture(RelationRole.CHILD, List.of(businessField("name", "name", "string")));

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(1,
                Map.of(EntityCapability.ENABLE, true), List.of()));

        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code)
                .contains("STALE_METADATA_VERSION", "CHILD_CAPABILITY_UNSUPPORTED");
    }

    @Test
    void shouldRejectUnknownFieldSpecDuringPreview() {
        Fixture fixture = fixture(RelationRole.MAIN, List.of());
        MetadataField field = businessField("subject", "subject", "missing_spec");
        when(fixture.fieldSpecService.requireFieldType("missing_spec"))
                .thenThrow(new net.ximatai.muyun.spring.common.exception.PlatformException("Field spec requires existing type: missing_spec"));

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, field))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIELD_DRAFT");
    }

    @Test
    void shouldRejectInvalidReferenceNameBeforePublication() {
        for (String name : List.of("title", "displayName")) {
            Fixture fixture = fixture(RelationRole.MAIN, List.of());
            MetadataField field = businessField(name, name.equals("title") ? "title" : "display_name", "name_spec");
            field.setTitleField(true);
            var spec = new FieldSpec();
            spec.setFieldType(name.equals("title") ? net.ximatai.muyun.spring.dynamic.metadata.FieldType.TEXT
                    : net.ximatai.muyun.spring.dynamic.metadata.FieldType.STRING);
            when(fixture.fieldSpecService.requireFieldType("name_spec")).thenReturn(spec);

            var result = fixture.service.preview("crm.customer", "main", command(3, Map.of(),
                    List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, field))));

            assertThat(result.valid()).as(name).isFalse();
            assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::message)
                    .anyMatch(message -> name.equals("title")
                            ? message.contains("STRING") && message.contains("TEXT") && !message.contains("物理列")
                            : message.contains("title") && message.contains("物理列") && !message.contains("TEXT"));
        }
    }

    @Test
    void shouldRejectDynamicRecordProtocolFieldNamesDuringPreview() {
        for (String fieldName : List.of("values", "attachments", "record")) {
            Fixture fixture = fixture(RelationRole.MAIN, List.of());
            MetadataField field = businessField(fieldName, fieldName, "string");

            MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                    Map.of(), List.of(new MetadataFieldChangeSetDraft(
                            MetadataFieldChangeSetDraft.Operation.ADD, null, field))));

            assertThat(result.valid()).as(fieldName).isFalse();
            assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code)
                    .as(fieldName).contains("INVALID_FIELD_DRAFT");
        }
    }

    @Test
    void shouldRejectFormulaDependentEvolutionBeforeSchemaPreflightIncludingDisable() {
        for (boolean disable : List.of(false, true)) {
            MetadataField existing = businessField("note", "note", "string");
            existing.setVersion(2);
            existing.setEnabled(true);
            Fixture fixture = fixture(RelationRole.MAIN, List.of(existing));
            doThrow(new net.ximatai.muyun.spring.common.exception.PlatformException("公式依赖该字段，不能修改"))
                    .when(fixture.fieldService).validateConfigurationFieldChange(any(), any());
            MetadataField proposed = businessField("note", "note", disable ? "string" : "integer");
            proposed.setEnabled(!disable);
            var result = fixture.service.preview("crm.customer", "main", command(3, Map.of(),
                    List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE, "field-0", 2, proposed))));
            assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("FIELD_CONFIGURATION_IN_USE");
            verify(fixture.schemaFacts, never()).countPhysicalRecords(anyString(), anyString(), any());
        }
    }

    @Test
    void shouldAllowAnyFieldSpecChangeWhenEntityHasNoData() {
        MetadataField existing = businessField("note", "note", "string");
        existing.setVersion(2);
        Fixture fixture = fixture(RelationRole.MAIN, List.of(existing));
        when(fixture.schemaFacts.countPhysicalRecords(anyString(), anyString(), any(Criteria.class))).thenReturn(0L);

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                        "field-0", 2, businessField("note", "note", "integer")))));

        assertThat(result.valid()).isTrue();
        assertThat(result.plan().fieldMutations().getFirst().field().getFieldSpecAlias()).isEqualTo("integer");
    }

    @Test
    void shouldAllowOnlyTextWideningWhenEntityHasData() {
        MetadataField existing = businessField("note", "note", "string");
        existing.setVersion(2);
        Fixture fixture = fixture(RelationRole.MAIN, List.of(existing));
        when(fixture.schemaFacts.countPhysicalRecords(anyString(), anyString(), any(Criteria.class))).thenReturn(1L);
        when(fixture.fieldSpecService.allowsDataSafeTarget("string", "text")).thenReturn(true);

        MetadataRelationChangeSetPreview allowed = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                        "field-0", 2, businessField("note", "note", "text")))));
        MetadataRelationChangeSetPreview rejected = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                        "field-0", 2, businessField("note", "note", "integer")))));

        assertThat(allowed.valid()).isTrue();
        assertThat(rejected.errors()).extracting(MetadataChangeSetValidationIssue::code)
                .contains("FIELD_SPEC_CHANGE_WITH_DATA");
    }

    @Test
    void shouldDescribeEveryPhysicalSchemaEffectOfAFieldUpdate() {
        MetadataField existing = businessField("note", "note", "string");
        existing.setVersion(2);
        Fixture fixture = fixture(RelationRole.MAIN, List.of(existing));
        when(fixture.schemaFacts.countPhysicalRecords(anyString(), anyString(), any(Criteria.class))).thenReturn(0L);
        MetadataField proposed = businessField("note", "note", "integer");
        proposed.setRequired(true);
        proposed.setUniqueField(true);
        proposed.setIndexed(true);

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                        "field-0", 2, proposed))));

        assertThat(result.valid()).isTrue();
        assertThat(result.schemaImpacts()).extracting(MetadataChangeSetSchemaImpact::operation)
                .containsExactly("ALTER_COLUMN_TYPE", "SET_NOT_NULL", "ADD_UNIQUE_INDEX", "ADD_INDEX");
        assertThat(result.schemaImpacts()).allSatisfy(impact -> {
            assertThat(impact.schemaName()).isEqualTo("public");
            assertThat(impact.tableName()).isEqualTo("crm_customer");
            assertThat(impact.columnName()).isEqualTo("note");
        });
    }

    @Test
    void shouldRejectStricterConstraintsWhenExistingRowsCannotBeProvenCompatible() {
        MetadataField existing = businessField("note", "note", "string");
        existing.setVersion(2);
        Fixture fixture = fixture(RelationRole.MAIN, List.of(existing));
        when(fixture.schemaFacts.countPhysicalRecords(anyString(), anyString(), any(Criteria.class))).thenReturn(7L);
        MetadataField proposed = businessField("note", "note", "string");
        proposed.setRequired(true);

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                        "field-0", 2, proposed))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code)
                .contains("FIELD_CONSTRAINT_CHANGE_WITH_DATA");
        assertThat(result.schemaImpacts()).isEmpty();
    }

    @Test
    void shouldStageReferencePropertyInsideTheSameFieldPlanAndFingerprint() {
        Fixture fixture = fixture(RelationRole.MAIN, List.of());
        MetadataField field = businessField("studentId", "student_id", "string");
        MetadataFieldReferenceConfig reference = new MetadataFieldReferenceConfig();
        reference.setTargetModuleAlias("education.student");
        reference.setTargetKeyField("studentNo");
        reference.setTargetLabelField("name");
        reference.setProjectionMappings("name:studentIdTitle");

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null,
                        MetadataFieldReferenceConfigDraft.fromConfig(reference), null)))));

        assertThat(result.valid()).isTrue();
        MetadataFieldPropertyChangeSetPlan property = result.plan().fieldMutations().getFirst().property();
        assertThat(property.kind()).isEqualTo(MetadataFieldPropertyKind.MODULE_REFERENCE);
        assertThat(property.referenceConfig()).extracting(MetadataFieldReferenceConfig::getTargetKeyField,
                MetadataFieldReferenceConfig::getTargetLabelField).containsExactly("studentNo", "name");
        assertThat(result.fieldImpacts().getFirst().description()).contains("模块引用", "education.student");
        assertThat(result.proposalFingerprint()).hasSize(64);
        reference.setRequireEnabled(true);
        MetadataRelationChangeSetPreview requiringEnabled = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null,
                        MetadataFieldReferenceConfigDraft.fromConfig(reference), null)))));
        assertThat(requiringEnabled.plan().fieldMutations().getFirst().property().referenceConfig().getRequireEnabled()).isTrue();
        assertThat(requiringEnabled.proposalFingerprint()).isNotEqualTo(result.proposalFingerprint());
    }

    @Test
    void shouldDescribeTheResolvedDictionaryBindingInTheFieldImpact() {
        Fixture fixture = fixture(RelationRole.MAIN, List.of());
        MetadataField field = businessField("attendanceStatus", "attendance_status", "string");
        MetadataFieldConfig dictionary = new MetadataFieldConfig();
        dictionary.setDictionaryApplicationAlias("education");
        dictionary.setDictionaryCategoryAlias("status");
        dictionary.setSelectionMode(net.ximatai.muyun.spring.common.option.OptionSelectionMode.SINGLE);

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.DICTIONARY, null, null,
                        dictionary)))));

        assertThat(result.valid()).isTrue();
        assertThat(result.fieldImpacts().getFirst().description())
                .contains("字典字段", "education.status", "SINGLE");
    }

    @Test
    void shouldRejectAReferencePropertyThatAlsoCarriesDictionaryBinding() {
        Fixture fixture = fixture(RelationRole.MAIN, List.of());
        MetadataField field = businessField("studentId", "student_id", "string");
        MetadataFieldReferenceConfig reference = new MetadataFieldReferenceConfig();
        reference.setTargetModuleAlias("education.student");
        MetadataFieldConfig dictionary = new MetadataFieldConfig();
        dictionary.setDictionaryCategoryAlias("status");

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null,
                        MetadataFieldReferenceConfigDraft.fromConfig(reference), dictionary)))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIELD_PROPERTY");
    }

    @Test
    void shouldRejectUnresolvableReferenceTargetDuringPreviewWithoutWriting() {
        Fixture fixture = fixture(RelationRole.MAIN, List.of());
        MetadataField field = businessField("studentId", "student_id", "string");
        MetadataFieldReferenceConfig reference = new MetadataFieldReferenceConfig();
        reference.setTargetModuleAlias("education.missing");
        doThrow(new net.ximatai.muyun.spring.common.exception.PlatformException("Reference config requires existing target module"))
                .when(fixture.referenceConfigService).validateDraft(any(), any(), any());

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null,
                        MetadataFieldReferenceConfigDraft.fromConfig(reference), null)))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIELD_PROPERTY");
        verify(fixture.fieldService, never()).insert(any());
    }

    @Test
    void shouldRejectUnknownDictionaryCategoryDuringPreviewWithoutWriting() {
        Fixture fixture = fixture(RelationRole.MAIN, List.of());
        MetadataField field = businessField("attendanceStatus", "attendance_status", "string");
        MetadataFieldConfig dictionary = new MetadataFieldConfig();
        dictionary.setDictionaryCategoryAlias("missing_status");
        doThrow(new net.ximatai.muyun.spring.common.exception.PlatformException("Dictionary category does not exist"))
                .when(fixture.fieldConfigService).validateDictionaryDraft(any(), any());

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.DICTIONARY, null, null, dictionary)))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIELD_PROPERTY");
        verify(fixture.fieldService, never()).insert(any());
    }

    @Test
    void shouldRejectNewPropertyChangeForLegacyModuleFieldBinding() {
        MetadataField existing = businessField("subjectId", "subject_id", "string");
        existing.setVersion(2);
        Fixture fixture = fixture(RelationRole.MAIN, List.of(existing));
        ModuleMetadataField legacy = new ModuleMetadataField();
        legacy.setMetadataFieldId("field-0");
        legacy.setReferenceModuleAlias("education.subject");
        when(fixture.moduleFieldService.findByRelationAndField("main", "field-0")).thenReturn(legacy);

        MetadataField draft = businessField("subjectId", "subject_id", "string");
        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                        "field-0", 2, draft, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC,
                        null, null, null)))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code)
                .contains("LEGACY_FIELD_PROPERTY_LOCKED");
    }

    @Test
    void shouldRejectLegacyLockedPropertyKindDuringPreview() {
        Fixture fixture = fixture(RelationRole.MAIN, List.of());
        MetadataField field = businessField("subjectId", "subject_id", "string");

        MetadataRelationChangeSetPreview result = fixture.service.preview("crm.customer", "main", command(3,
                Map.of(), List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.LEGACY_LOCKED)))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(MetadataChangeSetValidationIssue::code)
                .contains("LEGACY_FIELD_PROPERTY_LOCKED");
    }

    private MetadataRelationChangeSetPreviewCommand command(Integer version, Map<EntityCapability, Boolean> capabilities,
                                                             List<MetadataFieldChangeSetDraft> fields) {
        return new MetadataRelationChangeSetPreviewCommand(version, capabilities, fields);
    }

    private Fixture fixture(RelationRole role, List<MetadataField> fields) {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataService metadataService = mock(MetadataService.class);
        MetadataFieldService fieldService = mock(MetadataFieldService.class);
        FieldSpecService fieldSpecService = mock(FieldSpecService.class);
        MetadataFieldReferenceConfigService referenceConfigService = mock(MetadataFieldReferenceConfigService.class);
        MetadataFieldConfigService fieldConfigService = mock(MetadataFieldConfigService.class);
        DictionaryItemService dictionaryItems = mock(DictionaryItemService.class);
        MetadataFieldConfigService behaviorValidator = new MetadataFieldConfigService(new TestMemoryDao<>(), fieldService,
                metadataService, fieldSpecService, mock(DictionaryCategoryService.class),
                new DictionaryFieldValueValidator(dictionaryItems), relationService,
                mock(MetadataFieldProtectionConfigService.class), java.util.Optional.empty());
        doAnswer(call -> {
            behaviorValidator.validateDefaultValueDraft(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    call.getArgument(3), call.getArgument(4));
            return null;
        }).when(fieldConfigService).validateDefaultValueDraft(any(MetadataField.class), nullable(MetadataFieldConfig.class),
                nullable(MetadataFieldConfig.class), nullable(String.class), nullable(MetadataFieldConfig.class));
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        DynamicRecordService recordService = mock(DynamicRecordService.class);
        DynamicSchemaGovernanceFacts schemaFacts = mock(DynamicSchemaGovernanceFacts.class);
        PlatformModule module = new PlatformModule();
        module.setAlias("crm.customer");
        module.setModuleKind(ModuleKind.DYNAMIC);
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId("main");
        relation.setModuleAlias("crm.customer");
        relation.setMetadataId("metadata-1");
        relation.setRelationRole(role);
        Metadata metadata = new Metadata();
        metadata.setId("metadata-1");
        metadata.setVersion(3);
        metadata.setApplicationAlias("crm");
        metadata.setAlias("customer");
        metadata.setSchemaName("public");
        metadata.setTableName("crm_customer");
        for (int index = 0; index < fields.size(); index++) fields.get(index).setId(index == 0 && "enabled".equals(fields.get(index).getFieldName())
                ? "field-enabled" : "field-" + index);
        when(moduleService.select("crm.customer")).thenReturn(module);
        when(relationService.select("main")).thenReturn(relation);
        when(metadataService.select("metadata-1")).thenReturn(metadata);
        when(relationService.count(any(Criteria.class))).thenReturn(0L);
        when(fieldService.list(any(Criteria.class), any(PageRequest.class))).thenReturn(fields);
        when(fieldSpecService.requireFieldType(anyString())).thenReturn(new FieldSpec());
        when(recordService.schemaGovernanceFacts()).thenReturn(schemaFacts);
        return new Fixture(new MetadataRelationChangeSetPreviewService(moduleService, relationService, metadataService, fieldService,
                fieldSpecService, referenceConfigService, fieldConfigService, moduleFieldService, recordService), metadataService, fieldService,
                fieldSpecService, referenceConfigService, fieldConfigService, moduleFieldService, recordService, schemaFacts, dictionaryItems);
    }

    private MetadataField businessField(String name, String column, String spec) {
        MetadataField field = new MetadataField();
        field.setFieldName(name);
        field.setColumnName(column);
        field.setFieldSpecAlias(spec);
        field.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
        field.setSystemManaged(false);
        return field;
    }

    private record Fixture(MetadataRelationChangeSetPreviewService service,
                           MetadataService metadataService, MetadataFieldService fieldService,
                           FieldSpecService fieldSpecService, MetadataFieldReferenceConfigService referenceConfigService,
                           MetadataFieldConfigService fieldConfigService, ModuleMetadataFieldService moduleFieldService,
                           DynamicRecordService recordService, DynamicSchemaGovernanceFacts schemaFacts, DictionaryItemService dictionaryItems) {
    }
}
