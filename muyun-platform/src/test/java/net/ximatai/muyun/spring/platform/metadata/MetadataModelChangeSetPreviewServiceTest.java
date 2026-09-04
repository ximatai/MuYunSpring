package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MetadataModelChangeSetPreviewServiceTest {
    @Test
    void shouldRejectPartialSiblingRelationOrder() {
        Fixture fixture = fixture(List.of(main(), child("child-1"), child("child-2")), List.of());

        MetadataModelChangeSetPreview preview = fixture.service.preview("education.exam", new MetadataModelChangeSetPreviewCommand(
                List.of(), List.of(new MetadataModelRelationOrder("metadata-main", List.of("child-1"))), List.of()));

        assertThat(preview.valid()).isFalse();
        assertThat(preview.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_RELATION_ORDER");
    }

    @Test
    void shouldCompileOnlyMovableFieldOrderForChildRelation() {
        ModuleMetadataRelation child = child("child-1");
        MetadataField student = field("field-student", "studentId", false, MetadataFieldOwnership.BUSINESS);
        MetadataField foreignKey = field("field-exam", "examId", false, MetadataFieldOwnership.BUSINESS);
        Fixture fixture = fixture(List.of(main(), child), List.of(student, foreignKey));

        MetadataModelChangeSetPreview preview = fixture.service.preview("education.exam", new MetadataModelChangeSetPreviewCommand(
                List.of(), List.of(), List.of(new MetadataModelFieldOrder("child-1", List.of("field-student")))));

        assertThat(preview.valid()).isTrue();
        assertThat(preview.plan().fieldOrderPlans()).singleElement().satisfies(plan -> {
            assertThat(plan.relationId()).isEqualTo("child-1");
            assertThat(plan.entries()).extracting(MetadataModelFieldOrderPlan.Entry::fieldId).containsExactly("field-student");
        });
        verifyNoInteractions(fixture.relationPreviewService);
    }

    @Test
    void shouldAggregateRelationFieldAndSchemaImpactsForTheModelPreview() {
        ModuleMetadataRelation main = main();
        Fixture fixture = fixture(List.of(main), List.of());
        MetadataChangeSetFieldImpact fieldImpact = new MetadataChangeSetFieldImpact("ADD", "title", "title", false, "新增业务字段。");
        MetadataChangeSetSchemaImpact schemaImpact = new MetadataChangeSetSchemaImpact("ADD_COLUMN", "public", "exam", "title", "新增业务字段。");
        MetadataRelationChangeSetPreview relationPreview = new MetadataRelationChangeSetPreview("education.exam", "main",
                "metadata-main", 3, Set.of(), List.of(fieldImpact), List.of(schemaImpact), List.of(), List.of(), "relation-fingerprint",
                new MetadataRelationChangeSetPlan("metadata-main", 3, Set.of(), false, List.of()));
        when(fixture.relationPreviewService.preview(any(), any(), any())).thenReturn(relationPreview);

        MetadataModelChangeSetPreview preview = fixture.service.preview("education.exam", new MetadataModelChangeSetPreviewCommand(
                List.of(new MetadataModelRelationChangeSetDraft("main", 3, Map.of(), List.of())), List.of(), List.of()));

        assertThat(preview.fieldImpacts()).containsExactly(fieldImpact);
        assertThat(preview.schemaImpacts()).containsExactly(schemaImpact);
        verify(fixture.relationPreviewService).preview(eq("education.exam"), eq("main"), any());
    }

    @Test
    void shouldBindProposalFingerprintToRelationTopologyScope() {
        ModuleMetadataRelation main = main();
        Fixture fixture = fixture(List.of(main), List.of());
        MetadataRelationChangeSetPreview relationPreview = new MetadataRelationChangeSetPreview("education.exam", "main",
                "metadata-main", 3, Set.of(), List.of(), List.of(), List.of(), List.of(), "relation-fingerprint",
                new MetadataRelationChangeSetPlan("metadata-main", 3, Set.of(), false, List.of()));
        when(fixture.relationPreviewService.preview(any(), any(), any())).thenReturn(relationPreview);
        MetadataModelChangeSetPreviewCommand command = new MetadataModelChangeSetPreviewCommand(
                List.of(new MetadataModelRelationChangeSetDraft("main", 3, Map.of(), List.of())), List.of(), List.of());

        MetadataModelChangeSetPreview before = fixture.service.preview("education.exam", command);
        main.setRelationRole(RelationRole.CHILD);
        main.setParentMetadataId("metadata-other-parent");
        main.setForeignKey("examId");
        main.setVersion(4);
        MetadataModelChangeSetPreview after = fixture.service.preview("education.exam", command);

        assertThat(after.proposalFingerprint()).isNotEqualTo(before.proposalFingerprint());
    }

    private Fixture fixture(List<ModuleMetadataRelation> relations, List<MetadataField> fields) {
        PlatformModuleService modules = mock(PlatformModuleService.class);
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataFieldService fieldService = mock(MetadataFieldService.class);
        MetadataRelationChangeSetPreviewService relationPreviewService = mock(MetadataRelationChangeSetPreviewService.class);
        PlatformModule module = new PlatformModule();
        module.setAlias("education.exam");
        module.setModuleKind(ModuleKind.DYNAMIC);
        when(modules.select("education.exam")).thenReturn(module);
        when(relationService.list(any(Criteria.class), any(PageRequest.class))).thenReturn(relations);
        when(fieldService.list(any(Criteria.class), any(PageRequest.class))).thenReturn(fields);
        return new Fixture(new MetadataModelChangeSetPreviewService(modules, relationService, fieldService, relationPreviewService),
                relationPreviewService);
    }

    private ModuleMetadataRelation main() {
        ModuleMetadataRelation relation = relation("main", "metadata-main", null);
        relation.setRelationRole(RelationRole.MAIN);
        return relation;
    }

    private ModuleMetadataRelation child(String id) {
        ModuleMetadataRelation relation = relation(id, "metadata-" + id, "metadata-main");
        relation.setRelationRole(RelationRole.CHILD);
        relation.setForeignKey("examId");
        return relation;
    }

    private ModuleMetadataRelation relation(String id, String metadataId, String parentMetadataId) {
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId(id);
        relation.setModuleAlias("education.exam");
        relation.setMetadataId(metadataId);
        relation.setParentMetadataId(parentMetadataId);
        relation.setVersion(3);
        return relation;
    }

    private MetadataField field(String id, String name, boolean systemManaged, MetadataFieldOwnership ownership) {
        MetadataField field = new MetadataField();
        field.setId(id);
        field.setMetadataId("metadata-child-1");
        field.setFieldName(name);
        field.setColumnName(name);
        field.setFieldOwnership(ownership);
        field.setSystemManaged(systemManaged);
        field.setVersion(4);
        return field;
    }

    private record Fixture(MetadataModelChangeSetPreviewService service,
                           MetadataRelationChangeSetPreviewService relationPreviewService) {
    }
}
