package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicSchemaGovernanceFacts;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
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

class MetadataRelationChangeSetPreviewServiceTest {
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
        assertThat(result.proposalFingerprint()).hasSize(64);
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
                fieldSpecService, referenceConfigService, fieldConfigService, moduleFieldService, recordService, schemaFacts);
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
                           DynamicRecordService recordService, DynamicSchemaGovernanceFacts schemaFacts) {
    }
}
