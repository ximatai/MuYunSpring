package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
                schemaEnsureService, refreshCoordinator, snapshotService), metadataService, fieldService, schemaEnsureService,
                refreshCoordinator, metadata);
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
                           PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator, Metadata metadata) {
    }
}
