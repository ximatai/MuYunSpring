package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicSchemaGovernanceFacts;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataModelChangeSetApplyServiceTest {
    @Test
    void shouldLockCountAndDropAnEmptyFieldColumnInOneSchemaGovernanceSequence() {
        DynamicRecordService records = mock(DynamicRecordService.class);
        DynamicSchemaGovernanceFacts schemaFacts = mock(DynamicSchemaGovernanceFacts.class);
        FieldSpecService fieldSpecs = mock(FieldSpecService.class);
        Metadata metadata = new Metadata();
        metadata.setAlias("exam");
        metadata.setSchemaName("public");
        metadata.setTableName("education_exam");
        MetadataField field = new MetadataField();
        field.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
        field.setFieldForm(MetadataFieldForm.PHYSICAL);
        field.setFieldSpecAlias("integer");
        field.setColumnName("score");
        when(records.schemaGovernanceFacts()).thenReturn(schemaFacts);
        when(schemaFacts.lockExistingTableForSchemaMutation("public", "education_exam")).thenReturn(true);
        when(schemaFacts.countPhysicalRecords(eq("education.exam"), eq("exam"), any(Criteria.class))).thenReturn(0L);
        when(schemaFacts.databaseTypeForSchemaMutation()).thenReturn(DBInfo.Type.POSTGRESQL);

        new EmptyMetadataFieldSpecColumnRebuildService(records, fieldSpecs)
                .rebuildIfEmpty("education.exam", metadata, "string", field);

        InOrder schemaMutation = inOrder(schemaFacts);
        schemaMutation.verify(schemaFacts).lockExistingTableForSchemaMutation("public", "education_exam");
        schemaMutation.verify(schemaFacts).countPhysicalRecords(eq("education.exam"), eq("exam"), any(Criteria.class));
        schemaMutation.verify(schemaFacts).databaseTypeForSchemaMutation();
        schemaMutation.verify(schemaFacts).executeSchemaMutation(
                "alter table \"public\".\"education_exam\" drop column \"score\"");
    }

    @Test
    void shouldApplyTreeOrderAndActivateModuleExactlyOnce() {
        MetadataModelChangeSetPreviewService previews = mock(MetadataModelChangeSetPreviewService.class);
        MetadataRelationChangeSetApplyService relationApply = mock(MetadataRelationChangeSetApplyService.class);
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        MetadataService metadataService = mock(MetadataService.class);
        MetadataFieldService fields = mock(MetadataFieldService.class);
        PlatformMetadataSchemaEnsureService schema = mock(PlatformMetadataSchemaEnsureService.class);
        PlatformDynamicRuntimeRefreshCoordinator refresh = mock(PlatformDynamicRuntimeRefreshCoordinator.class);
        ModuleMetadataCapabilitySnapshotService snapshots = mock(ModuleMetadataCapabilitySnapshotService.class);
        ModuleMetadataRelation relation = relation();
        when(relations.select("child")).thenReturn(relation);
        when(snapshots.snapshot("education.exam", "child")).thenReturn(mock(ModuleMetadataCapabilitySnapshot.class));
        MetadataModelChangeSetPlan plan = new MetadataModelChangeSetPlan(List.of(),
                List.of(new MetadataModelRelationOrderPlan("parent", List.of(
                        new MetadataModelRelationOrderPlan.Entry("child", 2, 100)))), List.of());
        MetadataModelChangeSetPreview preview = new MetadataModelChangeSetPreview("education.exam", List.of(), List.of(), List.of(),
                "fingerprint", plan);
        MetadataModelChangeSetPreviewCommand proposal = new MetadataModelChangeSetPreviewCommand(List.of(), List.of(), List.of());
        when(previews.preview("education.exam", proposal)).thenReturn(preview);
        MetadataModelChangeSetApplyService service = new MetadataModelChangeSetApplyService(previews, relationApply, relations,
                metadataService, fields, schema, refresh, snapshots);

        MetadataModelChangeSetPublishResult result = service.apply("education.exam",
                new MetadataModelChangeSetApplyCommand(proposal, "fingerprint"));

        assertThat(result.affectedModuleAliases()).containsExactly("education.exam");
        assertThat(relation.getSortOrder()).isEqualTo(100);
        verify(relations).update(relation);
        verify(refresh).activateModulesNow(List.of("education.exam"));
        verify(schema, never()).ensureNow(any(Metadata.class));
        verifyNoRelationApply(relationApply);
    }

    @Test
    void shouldRejectRelationPlanWhenItsTopologyScopeChangedAfterPreview() {
        MetadataModelChangeSetPreviewService previews = mock(MetadataModelChangeSetPreviewService.class);
        MetadataRelationChangeSetApplyService relationApply = mock(MetadataRelationChangeSetApplyService.class);
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        ModuleMetadataRelation changed = relation();
        changed.setModuleAlias("education.exam");
        changed.setRelationRole(RelationRole.CHILD);
        when(relations.select("child")).thenReturn(changed);
        MetadataModelRelationPlan relationPlan = new MetadataModelRelationPlan("child", "education.exam", 2,
                "metadata-child", RelationRole.MAIN, null, null,
                new MetadataRelationChangeSetPlan("metadata-child", 1, java.util.Set.of(), false, List.of()), java.util.Set.of());
        MetadataModelChangeSetPlan plan = new MetadataModelChangeSetPlan(List.of(relationPlan), List.of(), List.of());
        MetadataModelChangeSetPreview preview = new MetadataModelChangeSetPreview("education.exam", List.of(), List.of(), List.of(),
                "fingerprint", plan);
        MetadataModelChangeSetPreviewCommand proposal = new MetadataModelChangeSetPreviewCommand(List.of(), List.of(), List.of());
        when(previews.preview("education.exam", proposal)).thenReturn(preview);
        MetadataModelChangeSetApplyService service = new MetadataModelChangeSetApplyService(previews, relationApply, relations,
                mock(MetadataService.class), mock(MetadataFieldService.class), mock(PlatformMetadataSchemaEnsureService.class),
                mock(PlatformDynamicRuntimeRefreshCoordinator.class), mock(ModuleMetadataCapabilitySnapshotService.class));

        assertThatThrownBy(() -> service.apply("education.exam", new MetadataModelChangeSetApplyCommand(proposal, "fingerprint")))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("relation scope is stale");
        verifyNoRelationApply(relationApply);
    }

    @Test
    void shouldRejectFieldOnlyProposalWhenRelationTopologyChangedAfterPreview() {
        PlatformModuleService modules = mock(PlatformModuleService.class);
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        MetadataFieldService fields = mock(MetadataFieldService.class);
        MetadataRelationChangeSetPreviewService relationPreviews = mock(MetadataRelationChangeSetPreviewService.class);
        PlatformModule module = new PlatformModule();
        module.setAlias("education.exam");
        module.setModuleKind(ModuleKind.DYNAMIC);
        ModuleMetadataRelation relation = relation();
        relation.setModuleAlias("education.exam");
        MetadataField field = new MetadataField();
        field.setId("field-title");
        field.setMetadataId("metadata-child");
        field.setFieldName("title");
        field.setColumnName("title");
        field.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
        field.setSystemManaged(false);
        field.setVersion(3);
        when(modules.select("education.exam")).thenReturn(module);
        when(relations.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(relation));
        when(relations.select("child")).thenReturn(relation);
        when(fields.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(field));
        MetadataModelChangeSetPreviewService previews = new MetadataModelChangeSetPreviewService(modules, relations, fields,
                relationPreviews);
        MetadataModelChangeSetPreviewCommand proposal = new MetadataModelChangeSetPreviewCommand(List.of(), List.of(),
                List.of(new MetadataModelFieldOrder("child", List.of("field-title"))));
        MetadataModelChangeSetPreview preflight = previews.preview("education.exam", proposal);
        assertThat(preflight.valid()).as("%s", preflight.errors()).isTrue();
        String fingerprint = preflight.proposalFingerprint();
        relation.setVersion(3);
        MetadataModelChangeSetApplyService service = new MetadataModelChangeSetApplyService(previews,
                mock(MetadataRelationChangeSetApplyService.class), relations, mock(MetadataService.class), fields,
                mock(PlatformMetadataSchemaEnsureService.class), mock(PlatformDynamicRuntimeRefreshCoordinator.class),
                mock(ModuleMetadataCapabilitySnapshotService.class));

        assertThatThrownBy(() -> service.apply("education.exam", new MetadataModelChangeSetApplyCommand(proposal, fingerprint)))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("fingerprint is stale");
        verify(fields, never()).update(any());
    }

    private void verifyNoRelationApply(MetadataRelationChangeSetApplyService service) {
        verify(service, never()).applyValidated(any(), any(), any(), any());
    }

    private ModuleMetadataRelation relation() {
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId("child");
        relation.setMetadataId("metadata-child");
        relation.setParentMetadataId("parent");
        relation.setVersion(2);
        relation.setRelationRole(RelationRole.CHILD);
        return relation;
    }
}
