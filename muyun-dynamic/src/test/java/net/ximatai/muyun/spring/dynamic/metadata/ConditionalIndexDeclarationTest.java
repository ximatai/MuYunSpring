package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.constraint.*;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.security.*;
import net.ximatai.muyun.spring.dynamic.schema.DynamicTableMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import static net.ximatai.muyun.spring.common.model.constraint.IndexCondition.Operator.*;
import static org.assertj.core.api.Assertions.*;

class ConditionalIndexDeclarationTest {
    private final ConditionalIndexDefinition live = new ConditionalIndexDefinition("ux_live_code", List.of("code"), true,
            List.of(new IndexCondition("deleted", IS_FALSE)));
    private EntityDefinition entity(FieldDefinition code) {
        return new EntityDefinition("item", "items", "Items", List.of(code, FieldDefinition.titleField()),
                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE)).withConditionalIndexes(live);
    }

    @Test void derivedCapabilityFieldsUseTheSameValidationAndColumnMapping() {
        var index = new ConditionalIndexDefinition("ix_authorized", List.of("id"), false,
                List.of(new IndexCondition("authUserId", IS_NOT_NULL)));
        var target = entity(FieldDefinition.string("code", "Code"))
                .withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE, EntityCapability.DATA_SCOPE).withConditionalIndexes(index);
        new ModuleDefinitionValidator().validateEntity(target);
        assertThat(new DynamicTableMapper().toTable(target).getIndexes()).singleElement()
                .satisfies(value -> assertThat(value.getPredicate()).isEqualTo("\"auth_user_id\" IS NOT NULL"));
        var unique = new ConditionalIndexDefinition("ux_authorized", List.of("authUserId"), true,
                List.of(new IndexCondition("deleted", IS_FALSE)));
        assertThat(new DynamicTableMapper().toTable(target.withConditionalIndexes(unique)).getIndexes()).singleElement()
                .satisfies(value -> assertThat(value.getColumns()).containsExactly("tenant_id", "auth_user_id"));
    }

    @Test void retainsConditionalIndexesWhenChangingUnrelatedMetadata() {
        var changed = entity(FieldDefinition.string("code", "Code"))
                .withFormulaRules().withTenantUniqueConstraints().withSortPartitionFields();
        assertThat(changed.conditionalIndexes()).containsExactly(live);
        assertThat(changed.resolvedTenantUniqueConstraints()).containsExactly(live.uniqueConstraint());
        assertThat(new DynamicTableMapper().toTable(changed).getIndexes()).singleElement()
                .satisfies(index -> assertThat(index.getName()).isEqualTo("ux_live_code"));
    }

    @Test void partialUniquenessDoesNotAuthorizeReferenceMatching() {
        var source = new EntityDefinition("entry", "entries", "Entries", List.of(FieldDefinition.string("itemCode", "Item").column("item_code")));
        var target = entity(FieldDefinition.string("code", "Code"));
        var reference = EntityReferenceDefinition.to("entry", "itemCode", "sample.items.item")
                .withRuntimeConfig("code", "missing", null, null, Set.of());
        var module = ModuleDefinition.builder("sample.items", "Items").entities(List.of(target, source))
                .references(List.of(reference)).build();
        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .hasMessageContaining("must be tenant-unique");
    }

    @Test void rejectsVirtualAndProtectedKeysAndConditions() {
        var protectedCode = FieldDefinition.string("code", "Code").protection(new FieldProtectionDefinition(
                FieldEncryptionMode.NONE, FieldSignatureMode.SIGNED, FieldMaskingPolicy.NONE));
        assertThatThrownBy(() -> new DynamicTableMapper().toTable(entity(protectedCode))).hasMessageContaining("protected");
        assertThatThrownBy(() -> new DynamicTableMapper().toTable(entity(FieldDefinition.string("code", "Code").virtual())))
                .hasMessageContaining("physical");
        var wrong = entity(FieldDefinition.string("code", "Code")).withConditionalIndexes(new ConditionalIndexDefinition(
                "ix_wrong_type", List.of("id"), false, List.of(new IndexCondition("code", IS_FALSE))));
        assertThatThrownBy(() -> new ModuleDefinitionValidator().validateEntity(wrong)).hasMessageContaining("boolean");
        var missing = entity(FieldDefinition.string("code", "Code")).withConditionalIndexes(new ConditionalIndexDefinition(
                "ix_missing", List.of("id"), false, List.of(new IndexCondition("missing", IS_NULL))));
        assertThatThrownBy(() -> new ModuleDefinitionValidator().validateEntity(missing)).hasMessageContaining("physical");
    }

    @Test void differentPredicatesOnSameKeysRemainSeparate() {
        var archived = new ConditionalIndexDefinition("ux_archived_code", List.of("code"), true,
                List.of(new IndexCondition("deleted", IS_TRUE)));
        var target = entity(FieldDefinition.string("code", "Code")).withConditionalIndexes(live, archived);
        assertThat(target.resolvedTenantUniqueConstraints()).hasSize(2);
        assertThat(new DynamicTableMapper().toTable(target).getIndexes()).hasSize(2);
        assertThatThrownBy(() -> target.withConditionalIndexes(live, live)).hasMessageContaining("duplicate");
    }
}
