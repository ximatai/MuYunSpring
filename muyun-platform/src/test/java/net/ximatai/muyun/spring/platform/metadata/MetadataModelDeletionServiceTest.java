package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicSchemaGovernanceFacts;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataModelDeletionServiceTest {
    @Test
    void shouldDeleteTheSystemFieldCatalogueOnlyInsideTheGovernedMetadataDeleteOperation() {
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        MetadataService metadata = mock(MetadataService.class);
        MetadataFieldService fields = mock(MetadataFieldService.class);
        ModuleMetadataFieldService moduleFields = mock(ModuleMetadataFieldService.class);
        PlatformMetadataEntityDefinitionCompiler compiler = mock(PlatformMetadataEntityDefinitionCompiler.class);
        PlatformMetadataSchemaEnsureService schema = mock(PlatformMetadataSchemaEnsureService.class);
        DynamicRecordService records = mock(DynamicRecordService.class);
        DynamicSchemaGovernanceFacts schemaFacts = mock(DynamicSchemaGovernanceFacts.class);
        PlatformDynamicRuntimeRefreshCoordinator refresh = mock(PlatformDynamicRuntimeRefreshCoordinator.class);
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId("relation-1");
        relation.setVersion(2);
        relation.setModuleAlias("education.exam");
        relation.setMetadataId("metadata-1");
        relation.setRelationRole(RelationRole.MAIN);
        Metadata metadataRecord = new Metadata();
        metadataRecord.setId("metadata-1");
        metadataRecord.setVersion(3);
        metadataRecord.setAlias("exam");
        metadataRecord.setSchemaName("public");
        metadataRecord.setTableName("education_exam");
        MetadataField systemField = new MetadataField();
        systemField.setId("field-id");
        systemField.setVersion(4);
        systemField.setMetadataId("metadata-1");
        systemField.setFieldName("id");
        systemField.setFieldOwnership(MetadataFieldOwnership.STANDARD);
        systemField.setSystemManaged(Boolean.TRUE);

        when(relations.select("relation-1")).thenReturn(relation);
        when(metadata.select("metadata-1")).thenReturn(metadataRecord);
        when(relations.count(any(Criteria.class))).thenReturn(0L);
        when(records.schemaGovernanceFacts()).thenReturn(schemaFacts);
        when(schemaFacts.lockExistingTableForSchemaMutation("public", "education_exam")).thenReturn(true);
        when(schemaFacts.countPhysicalRecords(eq("education.exam"), eq("exam"), any(Criteria.class))).thenReturn(0L);
        when(fields.list(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of(systemField));
        when(moduleFields.list(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenReturn(List.of());

        new MetadataModelDeletionService(relations, metadata, fields, moduleFields, compiler, schema, records, refresh)
                .deleteMetadata("education.exam", "relation-1");

        verify(fields).delete("field-id", 4);
        verify(relations).delete("relation-1", 2);
        verify(metadata).delete("metadata-1", 3);
        verify(schema).dropNow(metadataRecord);
        InOrder schemaMutation = inOrder(schemaFacts, schema);
        schemaMutation.verify(schemaFacts).lockExistingTableForSchemaMutation("public", "education_exam");
        schemaMutation.verify(schemaFacts).countPhysicalRecords(eq("education.exam"), eq("exam"), any(Criteria.class));
        schemaMutation.verify(schema).dropNow(metadataRecord);
        verify(refresh).deactivateModulesNow(List.of("education.exam"));
    }

    @Test
    void shouldRemoveRuntimeFieldMappingBeforeDeletingAnUnusedBusinessFieldAndItsColumn() {
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        MetadataService metadata = mock(MetadataService.class);
        MetadataFieldService fields = mock(MetadataFieldService.class);
        ModuleMetadataFieldService moduleFields = mock(ModuleMetadataFieldService.class);
        PlatformMetadataEntityDefinitionCompiler compiler = mock(PlatformMetadataEntityDefinitionCompiler.class);
        PlatformMetadataSchemaEnsureService schema = mock(PlatformMetadataSchemaEnsureService.class);
        DynamicRecordService records = mock(DynamicRecordService.class);
        DynamicSchemaGovernanceFacts schemaFacts = mock(DynamicSchemaGovernanceFacts.class);
        PlatformDynamicRuntimeRefreshCoordinator refresh = mock(PlatformDynamicRuntimeRefreshCoordinator.class);

        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId("relation-1");
        relation.setModuleAlias("education.exam");
        relation.setMetadataId("metadata-1");
        MetadataField field = new MetadataField();
        field.setId("field-1");
        field.setVersion(3);
        field.setMetadataId("metadata-1");
        field.setFieldName("title");
        field.setColumnName("title");
        field.setTitle("名称");
        field.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
        field.setSystemManaged(false);
        ModuleMetadataField moduleField = new ModuleMetadataField();
        moduleField.setId("module-field-1");
        moduleField.setVersion(4);
        Metadata metadataRecord = new Metadata();
        metadataRecord.setId("metadata-1");
        metadataRecord.setAlias("exam");
        metadataRecord.setSchemaName("public");
        metadataRecord.setTableName("education_exam");
        EntityDefinition previous = mock(EntityDefinition.class);

        when(relations.select("relation-1")).thenReturn(relation);
        when(fields.select("field-1")).thenReturn(field);
        when(metadata.select("metadata-1")).thenReturn(metadataRecord);
        when(records.schemaGovernanceFacts()).thenReturn(schemaFacts);
        when(schemaFacts.lockExistingTableForSchemaMutation("public", "education_exam")).thenReturn(true);
        when(schemaFacts.countPhysicalRecords(eq("education.exam"), eq("exam"), any(Criteria.class))).thenReturn(0L);
        when(moduleFields.list(any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(moduleField));
        when(compiler.compile(metadataRecord)).thenReturn(previous);

        MetadataModelDeletionService service = new MetadataModelDeletionService(relations, metadata, fields,
                moduleFields, compiler, schema, records, refresh);

        service.deleteField("education.exam", "relation-1", "field-1");

        InOrder deletion = inOrder(moduleFields, fields);
        deletion.verify(moduleFields).delete("module-field-1", 4);
        deletion.verify(fields).delete("field-1", 3);
        verify(schema).ensureNow("metadata-1", previous);
        InOrder schemaMutation = inOrder(schemaFacts, schema);
        schemaMutation.verify(schemaFacts).lockExistingTableForSchemaMutation("public", "education_exam");
        schemaMutation.verify(schemaFacts).countPhysicalRecords(eq("education.exam"), eq("exam"), any(Criteria.class));
        schemaMutation.verify(schema).ensureNow("metadata-1", previous);
        verify(refresh).activateModulesNow(List.of("education.exam"));
    }
}
