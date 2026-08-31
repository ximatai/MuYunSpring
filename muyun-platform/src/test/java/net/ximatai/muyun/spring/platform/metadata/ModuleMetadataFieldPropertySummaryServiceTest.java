package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModuleMetadataFieldPropertySummaryServiceTest {
    @Test
    void shouldResolveRelationReferencePropertyWithoutUsingLegacyModuleFieldConfig() {
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        MetadataFieldService fields = mock(MetadataFieldService.class);
        MetadataFieldReferenceConfigService references = mock(MetadataFieldReferenceConfigService.class);
        MetadataFieldConfigService configs = mock(MetadataFieldConfigService.class);
        ModuleMetadataFieldService moduleFields = mock(ModuleMetadataFieldService.class);
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId("main");
        relation.setModuleAlias("education.exam");
        relation.setMetadataId("exam-meta");
        MetadataField field = new MetadataField();
        field.setId("subject-field");
        field.setFieldName("subjectCategoryId");
        field.setFieldSpecAlias("string");
        MetadataFieldReferenceConfig reference = new MetadataFieldReferenceConfig();
        reference.setVersion(4);
        reference.setTargetModuleAlias("education.subject_category");
        reference.setTargetKeyField("code");
        reference.setTargetLabelField("title");
        reference.setProjectionMappings("title:subjectCategoryIdTitle");
        when(relations.select("main")).thenReturn(relation);
        when(fields.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(field));
        when(references.findForRelation("subject-field", "main")).thenReturn(reference);

        List<ModuleMetadataFieldPropertySummary> result = new ModuleMetadataFieldPropertySummaryService(
                relations, fields, references, configs, moduleFields).list("education.exam", "main");

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.kind()).isEqualTo(MetadataFieldPropertyKind.MODULE_REFERENCE);
            assertThat(summary.bindingVersion()).isEqualTo(4);
            assertThat(summary.reference().targetKeyField()).isEqualTo("code");
            assertThat(summary.reference().targetLabelField()).isEqualTo("title");
            assertThat(summary.reference().projectionMappings()).containsExactly("title:subjectCategoryIdTitle");
        });
    }

    @Test
    void shouldExposeLegacyModuleFieldBindingAsLockedInsteadOfASecondEditableTruth() {
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        MetadataFieldService fields = mock(MetadataFieldService.class);
        MetadataFieldReferenceConfigService references = mock(MetadataFieldReferenceConfigService.class);
        MetadataFieldConfigService configs = mock(MetadataFieldConfigService.class);
        ModuleMetadataFieldService moduleFields = mock(ModuleMetadataFieldService.class);
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId("main");
        relation.setModuleAlias("education.exam");
        relation.setMetadataId("exam-meta");
        MetadataField field = new MetadataField();
        field.setId("subject-field");
        field.setFieldName("subjectCategoryId");
        field.setFieldSpecAlias("string");
        ModuleMetadataField legacy = new ModuleMetadataField();
        legacy.setMetadataFieldId("subject-field");
        legacy.setReferenceModuleAlias("education.subject_category");
        legacy.setVersion(6);
        when(relations.select("main")).thenReturn(relation);
        when(fields.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(field));
        when(moduleFields.listByRelationId("main")).thenReturn(List.of(legacy));

        List<ModuleMetadataFieldPropertySummary> result = new ModuleMetadataFieldPropertySummaryService(
                relations, fields, references, configs, moduleFields).list("education.exam", "main");

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.kind()).isEqualTo(MetadataFieldPropertyKind.LEGACY_LOCKED);
            assertThat(summary.bindingVersion()).isEqualTo(6);
            assertThat(summary.reference().targetModuleAlias()).isEqualTo("education.subject_category");
        });
    }
}
