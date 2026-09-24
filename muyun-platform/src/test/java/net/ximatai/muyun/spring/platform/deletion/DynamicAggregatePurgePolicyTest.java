package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicAggregatePurgePolicyTest {
    private final EntityDefinition parentType = new EntityDefinition("parent", "parent", "Parent", List.of());
    private final EntityDefinition childType = new EntityDefinition("child", "child", "Child", List.of(
            FieldDefinition.string("parentId", "Parent").column("parent_id"), FieldDefinition.string("otherParentId", "Other").column("other_parent_id")));
    private final DynamicRecord parent = record(parentType, "parent-1");
    private final DynamicRecord child = record(childType, "child-1").setValue("parentId", "parent-1");
    private final DeletionEntry parentEntry = entry("parent-entry", "parent", "parent-1", null);
    private final DeletionEntry childEntry = entry("child-entry", "child", "child-1", "parent-entry");

    @Test
    void onlyDeclaredCascadeOwnershipProvidesAggregateAuthority() {
        assertThat(resolver(true, true).canPurgeAggregateChild(childEntry, parentEntry)).isTrue();
        assertThat(resolver(false, true).canPurgeAggregateChild(childEntry, parentEntry)).isFalse();
        assertThat(resolver(true, false).canPurgeAggregateChild(childEntry, parentEntry)).isFalse();
        assertThat(resolver(true, true).canPurgeAggregateChild(childEntry, null)).isFalse();
        childEntry.setParentEntryId("another-parent-entry");
        assertThat(resolver(true, true).canPurgeAggregateChild(childEntry, parentEntry)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"childVersion", "parentVersion", "owner", "tenant", "activeChild", "activeParent"})
    void rejectsChangedRetainedFacts(String change) {
        switch (change) {
            case "childVersion" -> child.setVersion(4);
            case "parentVersion" -> parent.setVersion(4);
            case "owner" -> child.setValue("parentId", "another-parent");
            case "tenant" -> child.setTenantId("another-tenant");
            case "activeChild" -> child.setDeleted(false);
            case "activeParent" -> parent.setDeleted(false);
        }
        assertThatThrownBy(() -> resolver(true, true).canPurgeAggregateChild(childEntry, parentEntry))
                .isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void sourceTenantOrOperationCannotBeSubstituted() {
        childEntry.setTenantId("another-tenant");
        assertThat(resolver(true, true).canPurgeAggregateChild(childEntry, parentEntry)).isFalse();
        childEntry.setTenantId("tenant");
        childEntry.setOperationId("another-source");
        assertThat(resolver(true, true).canPurgeAggregateChild(childEntry, parentEntry)).isFalse();
    }

    private DynamicDeletionRecoveryResourceResolver resolver(boolean owned, boolean cascade) {
        var runtime = mock(DynamicRecordRuntime.class);
        var registry = mock(DynamicModuleRegistry.class);
        when(runtime.registry()).thenReturn(registry);
        var relation = EntityRelationDefinition.child("children", "parent", "child", "parentId");
        var reference = EntityReferenceDefinition.to("child", "parentId", "test.order.parent")
                .withIntegrity(new ReferenceIntegrityPolicy(cascade ? ReferenceTargetUnavailablePolicy.CASCADE_DELETE
                        : ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY));
        when(registry.modules()).thenReturn(List.of(ModuleDefinition.builder("test.order", "Order")
                .entities(List.of(parentType, childType)).relations(owned ? List.of(relation) : List.of())
                .references(List.of(reference)).build()));
        var parents = mock(DynamicEntityService.class);
        var children = mock(DynamicEntityService.class);
        when(runtime.entityService("test.order", "parent")).thenReturn(parents);
        when(runtime.entityService("test.order", "child")).thenReturn(children);
        when(parents.selectIgnoreSoftDelete("parent-1")).thenReturn(parent);
        when(children.selectIgnoreSoftDelete("child-1")).thenReturn(child);
        return new DynamicDeletionRecoveryResourceResolver(Optional.of(runtime));
    }

    private DynamicRecord record(EntityDefinition entity, String id) {
        DynamicRecord record = new DynamicRecord(entity);
        record.setId(id); record.setVersion(3); record.setTenantId("tenant"); record.setDeleted(true);
        return record;
    }

    private DeletionEntry entry(String id, String alias, String recordId, String parentId) {
        DeletionEntry entry = new DeletionEntry();
        entry.setId(id); entry.setOperationId("source"); entry.setParentEntryId(parentId);
        entry.setTenantId("tenant"); entry.setResourceModuleAlias("test.order"); entry.setResourceEntityAlias(alias);
        entry.setResourceRecordId(recordId); entry.setResourceVersion(3);
        entry.setTriggerType(parentId == null ? DeletionEntryTrigger.DIRECT : DeletionEntryTrigger.CASCADE);
        return entry;
    }
}
