package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataRelationChangeSetApplyServiceTest {
    @Test
    void shouldPublishValidatedProposalEnsureOnceThenActivate() {
        Fixture fixture = fixture(validPreview("fingerprint"));
        MetadataField subject = field("subject", "subject");
        MetadataRelationChangeSetApplyCommand command = command("fingerprint", List.of(
                new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, subject)));

        MetadataRelationChangeSetPublishResult result = fixture.service.apply("crm.customer", "main", command);

        assertThat(result.affectedModuleAliases()).containsExactly("crm.customer");
        verify(fixture.metadataService).update(fixture.metadata);
        verify(fixture.fieldService).insert(org.mockito.ArgumentMatchers.argThat(field ->
                "subject".equals(field.getFieldName()) && "subject".equals(field.getColumnName())));
        verify(fixture.schemaEnsureService).ensureNow(fixture.metadata);
        verify(fixture.refreshCoordinator).activateByMetadataIdNow("metadata-1");
    }

    @Test
    void shouldRejectMismatchedFingerprintWithoutWriting() {
        Fixture fixture = fixture(validPreview("fresh"));

        assertThatThrownBy(() -> fixture.service.apply("crm.customer", "main", command("stale", List.of())))
                .isInstanceOf(PlatformException.class).hasMessageContaining("fingerprint");
        verify(fixture.metadataService, never()).update(any());
        verify(fixture.schemaEnsureService, never()).ensureNow(any(Metadata.class));
        verify(fixture.refreshCoordinator, never()).activateByMetadataIdNow(anyString());
    }

    @Test
    void shouldRejectInvalidPreviewBeforeWriting() {
        MetadataRelationChangeSetPreview invalid = new MetadataRelationChangeSetPreview("crm.customer", "main", "metadata-1", 3,
                Set.of(), List.of(), List.of(), List.of(), List.of(new MetadataChangeSetValidationIssue(
                MetadataChangeSetValidationIssue.Severity.ERROR, "NON_ADDITIVE_FIELD_DELETE", "title", "blocked")), "fingerprint");
        Fixture fixture = fixture(invalid);

        assertThatThrownBy(() -> fixture.service.apply("crm.customer", "main", command("fingerprint", List.of())))
                .isInstanceOf(PlatformException.class).hasMessageContaining("validation");
        verify(fixture.metadataService, never()).update(any());
        verify(fixture.refreshCoordinator, never()).activateByMetadataIdNow(anyString());
    }

    @Test
    void shouldNotActivateWhenDdlFails() {
        Fixture fixture = fixture(validPreview("fingerprint"));
        when(fixture.schemaEnsureService.ensureNow(any(Metadata.class))).thenThrow(new IllegalStateException("ddl failed"));

        assertThatThrownBy(() -> fixture.service.apply("crm.customer", "main", command("fingerprint", List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ddl failed");
        verify(fixture.refreshCoordinator, never()).activateByMetadataIdNow(anyString());
    }

    @Test
    void shouldRejectStaleBusinessFieldVersionBeforeWriting() {
        MetadataField planned = field("title", "title");
        MetadataRelationChangeSetPlan plan = new MetadataRelationChangeSetPlan("metadata-1", 3,
                Set.of(EntityCapability.ENABLE), false,
                List.of(new MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation.UPDATE, "field-title", 2, planned)));
        Fixture fixture = fixture(new MetadataRelationChangeSetPreview("crm.customer", "main", "metadata-1", 3,
                Set.of(EntityCapability.ENABLE), List.of(), List.of(), List.of(), List.of(), "fingerprint", plan));
        MetadataField current = field("title", "title");
        current.setId("field-title");
        current.setMetadataId("metadata-1");
        current.setVersion(3);
        when(fixture.fieldService.select("field-title")).thenReturn(current);

        assertThatThrownBy(() -> fixture.service.apply("crm.customer", "main", command("fingerprint", List.of())))
                .isInstanceOf(PlatformException.class).hasMessageContaining("field version is stale");
        verify(fixture.metadataService, never()).update(any());
        verify(fixture.schemaEnsureService, never()).ensureNow(any(Metadata.class));
    }

    @Test
    void shouldPublishReferencePropertyAfterCreatingItsSourceField() {
        MetadataFieldReferenceConfig reference = new MetadataFieldReferenceConfig();
        reference.setTargetModuleAlias("education.student");
        reference.setTargetKeyField("studentNo");
        reference.setTargetLabelField("name");
        MetadataFieldPropertyChangeSetPlan property = new MetadataFieldPropertyChangeSetPlan(
                MetadataFieldPropertyKind.MODULE_REFERENCE, null, reference, null);
        MetadataRelationChangeSetPlan plan = new MetadataRelationChangeSetPlan("metadata-1", 3,
                Set.of(EntityCapability.ENABLE), false,
                List.of(new MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field("studentId", "student_id"), property)));
        Fixture fixture = fixture(new MetadataRelationChangeSetPreview("crm.customer", "main", "metadata-1", 3,
                Set.of(EntityCapability.ENABLE), List.of(), List.of(), List.of(), List.of(), "fingerprint", plan));
        when(fixture.fieldService.insert(any(MetadataField.class))).thenReturn("field-student");

        fixture.service.apply("crm.customer", "main", command("fingerprint", List.of()));

        verify(fixture.referenceConfigService).insert(org.mockito.ArgumentMatchers.argThat(config ->
                "field-student".equals(config.getMetadataFieldId()) && "main".equals(config.getRelationId())
                        && "studentNo".equals(config.getTargetKeyField()) && "name".equals(config.getTargetLabelField())));
    }

    @Test
    void shouldCreateDictionaryRelationOverrideFromEffectiveBaseWithoutCopyingStorageShape() {
        MetadataFieldConfig dictionary = new MetadataFieldConfig();
        dictionary.setDictionaryApplicationAlias("education");
        dictionary.setDictionaryCategoryAlias("exam_attendance_status");
        MetadataFieldPropertyChangeSetPlan property = new MetadataFieldPropertyChangeSetPlan(
                MetadataFieldPropertyKind.DICTIONARY, 5, null, dictionary);
        MetadataRelationChangeSetPlan plan = new MetadataRelationChangeSetPlan("metadata-1", 3,
                Set.of(EntityCapability.ENABLE), false,
                List.of(new MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field("attendanceStatus", "attendance_status"), property)));
        Fixture fixture = fixture(new MetadataRelationChangeSetPreview("crm.customer", "main", "metadata-1", 3,
                Set.of(EntityCapability.ENABLE), List.of(), List.of(), List.of(), List.of(), "fingerprint", plan));
        when(fixture.fieldService.insert(any(MetadataField.class))).thenReturn("field-attendance");
        MetadataFieldConfig base = new MetadataFieldConfig();
        base.setVersion(5);
        base.setFieldLength(64);
        base.setPrecision(12);
        base.setScale(2);
        base.setQueryable(false);
        base.setDefaultValue("ATTENDED");
        base.setValidationRegex("[A-Z_]+");
        base.setCopyable(true);
        base.setWriteProtected(true);
        when(fixture.fieldConfigService.findRelationOverride("field-attendance", "main")).thenReturn(null);
        when(fixture.fieldConfigService.findByMetadataFieldId("field-attendance")).thenReturn(base);

        fixture.service.apply("crm.customer", "main", command("fingerprint", List.of()));

        verify(fixture.fieldConfigService).insert(org.mockito.ArgumentMatchers.argThat(config ->
                "field-attendance".equals(config.getMetadataFieldId()) && "main".equals(config.getRelationId())
                        && "exam_attendance_status".equals(config.getDictionaryCategoryAlias())
                        && Boolean.FALSE.equals(config.getQueryable())
                        && "ATTENDED".equals(config.getDefaultValue())
                        && "[A-Z_]+".equals(config.getValidationRegex())
                        && Boolean.TRUE.equals(config.getCopyable()) && Boolean.TRUE.equals(config.getWriteProtected())
                        && config.getFieldLength() == null && config.getPrecision() == null && config.getScale() == null));
    }

    @Test
    void shouldRejectLegacyLockedPropertyEvenIfAnInvalidPreviewPlanIsInjected() {
        MetadataFieldPropertyChangeSetPlan property = new MetadataFieldPropertyChangeSetPlan(
                MetadataFieldPropertyKind.LEGACY_LOCKED, null, null, null);
        MetadataRelationChangeSetPlan plan = new MetadataRelationChangeSetPlan("metadata-1", 3,
                Set.of(EntityCapability.ENABLE), false,
                List.of(new MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                        field("legacySubject", "legacy_subject"), property)));
        Fixture fixture = fixture(new MetadataRelationChangeSetPreview("crm.customer", "main", "metadata-1", 3,
                Set.of(EntityCapability.ENABLE), List.of(), List.of(), List.of(), List.of(), "fingerprint", plan));
        when(fixture.fieldService.insert(any(MetadataField.class))).thenReturn("field-legacy");

        assertThatThrownBy(() -> fixture.service.apply("crm.customer", "main", command("fingerprint", List.of())))
                .isInstanceOf(PlatformException.class).hasMessageContaining("read-only");
        verify(fixture.metadataService, never()).update(any());
    }

    @Test
    void shouldRemoveDataScopeColumnsWhenThereAreNoBusinessRecords() {
        Fixture fixture = fixture(validPreview("fingerprint"));
        fixture.metadata.setDataScopeEnabled(true);
        EntityDefinition previous = mock(EntityDefinition.class);
        when(fixture.entityDefinitionCompiler.compile(fixture.metadata)).thenReturn(previous);
        when(fixture.recordService.count(eq("crm.customer"), eq("customer"), any(Criteria.class))).thenReturn(0L);

        fixture.service.disableDataScope("crm.customer", "metadata-1");

        assertThat(fixture.metadata.getDataScopeEnabled()).isFalse();
        verify(fixture.metadataService).update(fixture.metadata);
        verify(fixture.schemaEnsureService).ensureNow("metadata-1", previous);
        verify(fixture.refreshCoordinator).activateByMetadataIdNow("metadata-1");
    }

    @Test
    void shouldRejectDataScopeRemovalWhenBusinessRecordsExist() {
        Fixture fixture = fixture(validPreview("fingerprint"));
        fixture.metadata.setDataScopeEnabled(true);
        when(fixture.recordService.count(eq("crm.customer"), eq("customer"), any(Criteria.class))).thenReturn(1L);

        assertThatThrownBy(() -> fixture.service.disableDataScope("crm.customer", "metadata-1"))
                .isInstanceOf(PlatformException.class).hasMessageContaining("不能停用");
        verify(fixture.metadataService, never()).update(fixture.metadata);
    }

    @Test
    void shouldRemoveEnableCapabilityAndItsManagedFieldWhenThereAreNoBusinessRecords() {
        Fixture fixture = fixture(validPreview("fingerprint"));
        fixture.metadata.setCapabilityDeclarations(Set.of("ENABLE"));
        MetadataField enabled = field("enabled", "enabled");
        enabled.setId("field-enabled");
        enabled.setVersion(2);
        enabled.setFieldOwnership(MetadataFieldOwnership.STANDARD);
        enabled.setSystemManaged(Boolean.TRUE);
        when(fixture.fieldService.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(enabled));
        EntityDefinition previous = mock(EntityDefinition.class);
        when(fixture.entityDefinitionCompiler.compile(fixture.metadata)).thenReturn(previous);
        when(fixture.recordService.count(eq("crm.customer"), eq("customer"), any(Criteria.class))).thenReturn(0L);

        fixture.service.disableEnable("crm.customer", "metadata-1");

        assertThat(fixture.metadata.getCapabilityDeclarations()).isEmpty();
        verify(fixture.fieldService).delete("field-enabled", 2);
        verify(fixture.schemaEnsureService).ensureNow("metadata-1", previous);
    }

    @Test
    void shouldRemoveSortCapabilityAndItsManagedFieldWhenThereAreNoBusinessRecords() {
        Fixture fixture = fixture(validPreview("fingerprint"));
        fixture.metadata.setCapabilityDeclarations(Set.of("SORT"));
        MetadataField sort = field("sortOrder", "sort_order");
        sort.setId("field-sort");
        sort.setVersion(2);
        sort.setFieldOwnership(MetadataFieldOwnership.STANDARD);
        sort.setSystemManaged(Boolean.TRUE);
        sort.setSortableField(Boolean.TRUE);
        when(fixture.fieldService.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(sort));
        EntityDefinition previous = mock(EntityDefinition.class);
        when(fixture.entityDefinitionCompiler.compile(fixture.metadata)).thenReturn(previous);
        when(fixture.recordService.count(eq("crm.customer"), eq("customer"), any(Criteria.class))).thenReturn(0L);

        fixture.service.disableSort("crm.customer", "metadata-1");

        assertThat(fixture.metadata.getCapabilityDeclarations()).isEmpty();
        verify(fixture.fieldService).delete("field-sort", 2);
        verify(fixture.schemaEnsureService).ensureNow("metadata-1", previous);
    }

    @Test
    void shouldRemoveTreeCapabilityAndItsManagedFieldWhenThereAreNoBusinessRecords() {
        Fixture fixture = fixture(validPreview("fingerprint"));
        fixture.metadata.setCapabilityDeclarations(Set.of("TREE", "SORT"));
        MetadataField parent = field("parentId", "parent_id");
        parent.setId("field-parent");
        parent.setVersion(2);
        parent.setFieldOwnership(MetadataFieldOwnership.STANDARD);
        parent.setSystemManaged(Boolean.TRUE);
        when(fixture.fieldService.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(parent));
        EntityDefinition previous = mock(EntityDefinition.class);
        when(fixture.entityDefinitionCompiler.compile(fixture.metadata)).thenReturn(previous);
        when(fixture.recordService.count(eq("crm.customer"), eq("customer"), any(Criteria.class))).thenReturn(0L);

        fixture.service.disableTree("crm.customer", "metadata-1");

        assertThat(fixture.metadata.getCapabilityDeclarations()).containsExactly("SORT");
        verify(fixture.fieldService).delete("field-parent", 2);
        verify(fixture.schemaEnsureService).ensureNow("metadata-1", previous);
    }

    private MetadataRelationChangeSetApplyCommand command(String fingerprint, List<MetadataFieldChangeSetDraft> fields) {
        return new MetadataRelationChangeSetApplyCommand(new MetadataRelationChangeSetPreviewCommand(3,
                Map.of(EntityCapability.ENABLE, true), fields), fingerprint);
    }

    private MetadataRelationChangeSetPreview validPreview(String fingerprint) {
        return new MetadataRelationChangeSetPreview("crm.customer", "main", "metadata-1", 3,
                Set.of(EntityCapability.ENABLE), List.of(), List.of(), List.of(), List.of(), fingerprint,
                new MetadataRelationChangeSetPlan("metadata-1", 3, Set.of(EntityCapability.ENABLE), true,
                        List.of(new MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                                field("subject", "subject")))));
    }

    private Fixture fixture(MetadataRelationChangeSetPreview preview) {
        MetadataRelationChangeSetPreviewService previewService = mock(MetadataRelationChangeSetPreviewService.class);
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataService metadataService = mock(MetadataService.class);
        MetadataFieldService fieldService = mock(MetadataFieldService.class);
        PlatformMetadataSchemaEnsureService schemaEnsureService = mock(PlatformMetadataSchemaEnsureService.class);
        PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator = mock(PlatformDynamicRuntimeRefreshCoordinator.class);
        ModuleMetadataCapabilitySnapshotService snapshotService = mock(ModuleMetadataCapabilitySnapshotService.class);
        MetadataFieldReferenceConfigService referenceConfigService = mock(MetadataFieldReferenceConfigService.class);
        MetadataFieldConfigService fieldConfigService = mock(MetadataFieldConfigService.class);
        PlatformMetadataEntityDefinitionCompiler entityDefinitionCompiler = mock(PlatformMetadataEntityDefinitionCompiler.class);
        DynamicRecordService recordService = mock(DynamicRecordService.class);
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId("main");
        relation.setModuleAlias("crm.customer");
        relation.setMetadataId("metadata-1");
        relation.setRelationRole(RelationRole.MAIN);
        Metadata metadata = new Metadata();
        metadata.setId("metadata-1");
        metadata.setVersion(3);
        metadata.setApplicationAlias("crm");
        metadata.setAlias("customer");
        metadata.setSchemaName("public");
        metadata.setTableName("crm_customer");
        when(previewService.preview(anyString(), anyString(), any())).thenReturn(preview);
        when(relationService.select("main")).thenReturn(relation);
        when(relationService.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(relation));
        when(metadataService.select("metadata-1")).thenReturn(metadata);
        when(fieldService.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        return new Fixture(new MetadataRelationChangeSetApplyService(previewService, relationService, metadataService, fieldService,
                schemaEnsureService, refreshCoordinator, snapshotService, referenceConfigService, fieldConfigService,
                entityDefinitionCompiler, recordService), metadataService,
                fieldService, schemaEnsureService, refreshCoordinator, metadata, referenceConfigService, fieldConfigService,
                entityDefinitionCompiler, recordService);
    }

    private MetadataField field(String fieldName, String columnName) {
        MetadataField field = new MetadataField();
        field.setFieldName(fieldName);
        field.setColumnName(columnName);
        field.setFieldSpecAlias("string");
        field.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
        return field;
    }

    private record Fixture(MetadataRelationChangeSetApplyService service, MetadataService metadataService,
                           MetadataFieldService fieldService, PlatformMetadataSchemaEnsureService schemaEnsureService,
                           PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator, Metadata metadata,
                           MetadataFieldReferenceConfigService referenceConfigService,
                           MetadataFieldConfigService fieldConfigService,
                           PlatformMetadataEntityDefinitionCompiler entityDefinitionCompiler,
                           DynamicRecordService recordService) {
    }
}
