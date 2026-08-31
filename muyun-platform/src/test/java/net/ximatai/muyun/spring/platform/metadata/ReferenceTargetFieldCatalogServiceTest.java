package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateKey;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateField;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceTargetFieldCatalogServiceTest {
    @AfterEach
    void resetResolver() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void shouldListOnlyDynamicUniqueKeysAndReadablePhysicalLabels() {
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        PlatformModuleService modules = mock(PlatformModuleService.class);
        MetadataFieldService fields = mock(MetadataFieldService.class);
        MetadataFieldProtectionConfigService protections = mock(MetadataFieldProtectionConfigService.class);
        ModuleMetadataRelation source = relation("source-main", "education.exam", "exam-meta");
        ModuleMetadataRelation target = relation("target-main", "education.student", "student-meta");
        PlatformModule targetModule = new PlatformModule();
        targetModule.setAlias("education.student");
        targetModule.setModuleKind(ModuleKind.DYNAMIC);
        MetadataField studentNo = field("student-no", "studentNo", "学号");
        studentNo.setUniqueField(true);
        MetadataField name = field("name", "name", "姓名");
        name.setTitleField(true);
        MetadataField duplicate = field("duplicate", "duplicate", "重复值");
        MetadataField virtual = field("virtual", "summary", "摘要");
        virtual.setFieldForm(MetadataFieldForm.VIRTUAL);
        when(relations.select("source-main")).thenReturn(source);
        when(modules.select("education.student")).thenReturn(targetModule);
        when(relations.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(target));
        when(fields.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(studentNo, name, duplicate, virtual));
        when(protections.definition(any())).thenReturn(FieldProtectionDefinition.NONE);

        ReferenceTargetFieldCatalog catalog = new ReferenceTargetFieldCatalogService(relations, modules, fields, protections)
                .list("education.exam", "source-main", "education.student", "student-meta");

        assertThat(catalog.targetMetadataId()).isEqualTo("student-meta");
        assertThat(catalog.keyFields()).extracting(ReferenceTargetFieldCandidate::fieldName)
                .containsExactly("id", "studentNo");
        assertThat(catalog.labelFields()).extracting(ReferenceTargetFieldCandidate::fieldName)
                .containsExactly("duplicate", "name", "studentNo");
        assertThat(catalog.labelFields()).filteredOn(ReferenceTargetFieldCandidate::defaultField)
                .extracting(ReferenceTargetFieldCandidate::fieldName).containsExactly("name");
        verify(fields).list(any(Criteria.class), any(PageRequest.class));
    }

    @Test
    void shouldRejectMetadataOutsideTheDynamicMainTarget() {
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        PlatformModuleService modules = mock(PlatformModuleService.class);
        ModuleMetadataRelation source = relation("source-main", "education.exam", "exam-meta");
        ModuleMetadataRelation target = relation("target-main", "education.student", "student-meta");
        PlatformModule targetModule = new PlatformModule();
        targetModule.setAlias("education.student");
        targetModule.setModuleKind(ModuleKind.DYNAMIC);
        when(relations.select("source-main")).thenReturn(source);
        when(modules.select("education.student")).thenReturn(targetModule);
        when(relations.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(target));

        assertThatThrownBy(() -> new ReferenceTargetFieldCatalogService(relations, modules,
                mock(MetadataFieldService.class), mock(MetadataFieldProtectionConfigService.class))
                .list("education.exam", "source-main", "education.student", "child-meta"))
                .hasMessageContaining("not the target module main entity");
    }

    @Test
    void shouldExposeOnlyProvenStaticIdKeyAndReadableTitleLabel() {
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        PlatformModuleService modules = mock(PlatformModuleService.class);
        ModuleMetadataRelation source = relation("source-main", "education.exam", "exam-meta");
        PlatformModule targetModule = new PlatformModule();
        targetModule.setAlias("iam.user");
        targetModule.setModuleKind(ModuleKind.STATIC);
        @SuppressWarnings("unchecked") ReferenceAbility<StaticUser> ability = mock(ReferenceAbility.class);
        org.mockito.Mockito.doReturn(StaticUser.class).when(ability).modelClass();
        when(ability.referenceCandidateKeys()).thenReturn(List.of(
                new ReferenceCandidateKey("id", true, true),
                new ReferenceCandidateKey("employeeNo", true, true)));
        when(ability.referenceCandidateLabels()).thenReturn(List.of(
                new ReferenceCandidateField("displayName", false),
                new ReferenceCandidateField("title", true)));
        ReferenceTarget user = ReferenceTarget.of("iam", "user");
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> user.equals(target)
                ? java.util.Optional.of(ability) : java.util.Optional.empty());
        when(relations.select("source-main")).thenReturn(source);
        when(modules.select("iam.user")).thenReturn(targetModule);

        ReferenceTargetFieldCatalog catalog = new ReferenceTargetFieldCatalogService(relations, modules,
                mock(MetadataFieldService.class), null)
                .list("education.exam", "source-main", "iam.user", null);

        assertThat(catalog.targetMetadataId()).isNull();
        assertThat(catalog.keyFields()).extracting(ReferenceTargetFieldCandidate::fieldName)
                .containsExactly("id", "employeeNo");
        assertThat(catalog.labelFields()).extracting(ReferenceTargetFieldCandidate::fieldName)
                .containsExactly("displayName", "title");
    }

    private ModuleMetadataRelation relation(String id, String moduleAlias, String metadataId) {
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId(id);
        relation.setModuleAlias(moduleAlias);
        relation.setMetadataId(metadataId);
        relation.setRelationRole(RelationRole.MAIN);
        return relation;
    }

    private MetadataField field(String id, String name, String title) {
        MetadataField field = new MetadataField();
        field.setId(id);
        field.setFieldName(name);
        field.setTitle(title);
        return field;
    }

    private static class StaticUser extends StandardTitledEntity {
    }
}
