package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
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

    private MetadataRelationChangeSetPreviewCommand command(Integer version, Map<EntityCapability, Boolean> capabilities,
                                                             List<MetadataFieldChangeSetDraft> fields) {
        return new MetadataRelationChangeSetPreviewCommand(version, capabilities, fields);
    }

    private Fixture fixture(RelationRole role, List<MetadataField> fields) {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataService metadataService = mock(MetadataService.class);
        MetadataFieldService fieldService = mock(MetadataFieldService.class);
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
        return new Fixture(new MetadataRelationChangeSetPreviewService(moduleService, relationService, metadataService, fieldService),
                metadataService, fieldService);
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
                           MetadataService metadataService, MetadataFieldService fieldService) {
    }
}
