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
        assertThat(plan.implicitRuntimeFields()).isEmpty();
        assertThatThrownBy(() -> MetadataCapabilityCatalog.requireDeclaration("DATA_SCOPE"))
                .isInstanceOf(PlatformException.class).hasMessageContaining("not declarable");
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
