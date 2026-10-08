package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataCapabilityCatalogTest {
    @Test
    void designDiscoveryUsesTheStandardSchemaAndTheSameRecordNameValidation() {
        var contract = MetadataCapabilityCatalog.designContract();
        assertThat(contract.inheritedFields()).isEqualTo(
                net.ximatai.muyun.spring.common.schema.StandardEntitySchema.fieldNames());
        assertThat(contract.declarableCapabilities()).isEqualTo(MetadataCapabilityCatalog.plan(
                Set.of(EntityCapability.TREE, EntityCapability.SORT, EntityCapability.ENABLE, EntityCapability.RECYCLE_BIN,
                        EntityCapability.APPROVAL)));
        assertThat(contract.recordName().accepts("title", "title", "STRING")).isTrue();
        assertThat(contract.recordName().accepts("customerName", "customer_name", "STRING")).isFalse();
        assertThat(contract.recordName().accepts("title", "title", "TEXT")).isFalse();
    }

    @Test
    void shouldUseFieldInferenceOnlyForLegacyNullDeclarations() {
        Metadata legacy = metadata();
        MetadataField parent = field("parentId", "parent_id");
        MetadataCapabilityResolution legacyResolution = MetadataCapabilityCatalog.resolve(legacy, RelationRole.MAIN,
                List.of(parent));

        assertThat(legacyResolution.legacyFieldInference()).isTrue();
        assertThat(legacyResolution.capabilities()).contains(EntityCapability.TREE, EntityCapability.SORT);

        Metadata governed = metadata();
        governed.setCapabilityDeclarations(Set.of("ENABLE"));
        MetadataCapabilityResolution governedResolution = MetadataCapabilityCatalog.resolve(governed, RelationRole.MAIN,
                List.of(parent));

        assertThat(governedResolution.legacyFieldInference()).isFalse();
        assertThat(governedResolution.capabilities()).containsExactly(EntityCapability.ENABLE);
    }

    @Test
    void shouldPlanTreeDependencyWithoutExpandingDataScopeDeclaration() {
        MetadataCapabilityPlan plan = MetadataCapabilityCatalog.plan(Set.of(EntityCapability.TREE));

        assertThat(plan.capabilities()).contains(EntityCapability.TREE, EntityCapability.SORT);
        assertThat(plan.metadataFields()).extracting(ModuleMetadataCapabilityFieldContribution::fieldName)
                .containsExactly("parentId", "sortOrder");
        assertThatThrownBy(() -> MetadataCapabilityCatalog.requireDeclaration("DATA_SCOPE"))
                .isInstanceOf(PlatformException.class).hasMessageContaining("not declarable");
    }

    @Test
    void recycleBinDeclarationMustNotInventFieldsOrBecomeImplicitInLegacyModels() {
        Metadata metadata = metadata();
        metadata.setCapabilityDeclarations(Set.of("RECYCLE_BIN"));
        var resolution = MetadataCapabilityCatalog.resolve(metadata, RelationRole.MAIN, List.of());
        assertThat(resolution.capabilities()).containsExactly(EntityCapability.RECYCLE_BIN);
        assertThat(resolution.plan().metadataFields()).isEmpty();
        assertThat(MetadataCapabilityCatalog.isMutableInFirstRelease(EntityCapability.RECYCLE_BIN)).isTrue();
        assertThatThrownBy(() -> MetadataCapabilityCatalog.resolve(metadata, RelationRole.CHILD, List.of()))
                .isInstanceOf(PlatformException.class);
        assertThat(MetadataCapabilityCatalog.resolve(metadata(), RelationRole.MAIN, List.of()).capabilities())
                .doesNotContain(EntityCapability.RECYCLE_BIN);
    }

    @Test
    void shouldRejectDeclaredCapabilityForChildRelation() {
        Metadata metadata = metadata();
        metadata.setCapabilityDeclarations(Set.of("ENABLE"));

        assertThatThrownBy(() -> MetadataCapabilityCatalog.resolve(metadata, RelationRole.CHILD, List.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Child metadata cannot declare");
    }

    @Test
    void approvalDeclarationOwnsFiveCanonicalSummaryFieldsAndOnlyLegacyUsesFieldInference() {
        Metadata governed = metadata(); governed.setCapabilityDeclarations(Set.of("APPROVAL"));
        var resolution = MetadataCapabilityCatalog.resolve(governed, RelationRole.MAIN, List.of());
        assertThat(resolution.capabilities()).containsExactly(EntityCapability.APPROVAL);
        assertThat(resolution.plan().metadataFields()).extracting(ModuleMetadataCapabilityFieldContribution::fieldName)
                .containsExactly("approvalInstanceId", "approvalStatus", "approvalSubmittedBy", "approvalSubmittedAt", "approvalCompletedAt");
        assertThat(MetadataCapabilityCatalog.mergeDeclaredMetadataFields(resolution, List.of()))
                .isEqualTo(net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields.approvalFields());
        assertThat(MetadataCapabilityCatalog.isMutableInFirstRelease(EntityCapability.APPROVAL)).isTrue();
        assertThatThrownBy(() -> MetadataCapabilityCatalog.resolve(governed, RelationRole.CHILD, List.of()))
                .hasMessageContaining("Child metadata cannot declare");
        var saved = field("approvalStatus", "approval_status");
        assertThat(MetadataCapabilityCatalog.resolve(metadata(), RelationRole.MAIN, List.of(saved)).capabilities())
                .contains(EntityCapability.APPROVAL);
        governed.setCapabilityDeclarations(Set.of());
        assertThat(MetadataCapabilityCatalog.resolve(governed, RelationRole.MAIN, List.of(saved)).capabilities())
                .doesNotContain(EntityCapability.APPROVAL);
        saved.setFieldOwnership(MetadataFieldOwnership.STANDARD); saved.setSystemManaged(true); saved.setFieldSpecAlias("string");
        assertThat(MetadataCapabilityCatalog.managedDefinition(saved).length())
                .isEqualTo(net.ximatai.muyun.spring.common.schema.PlatformAbilityFields.APPROVAL_STATUS_LENGTH);
    }

    @Test
    void shouldRejectCapabilityDeclarationDuringChildRelationValidation() {
        Metadata metadata = metadata();
        metadata.setCapabilityDeclarations(Set.of("ENABLE"));

        assertThatThrownBy(() -> ModuleMetadataCapabilityPolicy.validateChildMetadataConfiguration(metadata))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Child metadata cannot declare");
    }

    @Test
    void shouldMergeDeclaredManagedFieldsOnceButKeepLegacyFieldsUntouched() {
        Metadata governed = metadata();
        governed.setCapabilityDeclarations(Set.of("TREE", "ENABLE"));
        MetadataCapabilityResolution governedResolution = MetadataCapabilityCatalog.resolve(governed, RelationRole.MAIN,
                List.of(field("parentId", "parent_id")));

        assertThat(MetadataCapabilityCatalog.mergeDeclaredMetadataFields(governedResolution,
                List.of(net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition.string("parentId", "Parent")
                        .column("parent_id"))))
                .extracting(field -> field.fieldName())
                .containsExactly("parentId", "sortOrder", "enabled");
        assertThat(MetadataCapabilityCatalog.mergeDeclaredMetadataFields(governedResolution,
                List.of(net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition.string("parentId", "Parent")
                        .column("parent_id")))
                .get(0).length()).isEqualTo(32);

        Metadata legacy = metadata();
        MetadataCapabilityResolution legacyResolution = MetadataCapabilityCatalog.resolve(legacy, RelationRole.MAIN,
                List.of(field("parentId", "parent_id")));
        assertThat(MetadataCapabilityCatalog.mergeDeclaredMetadataFields(legacyResolution, List.of()))
                .isEmpty();
    }

    private Metadata metadata() {
        Metadata metadata = new Metadata();
        metadata.setAlias("customer");
        return metadata;
    }

    private MetadataField field(String name, String column) {
        MetadataField field = new MetadataField();
        field.setFieldName(name);
        field.setColumnName(column);
        return field;
    }
}
