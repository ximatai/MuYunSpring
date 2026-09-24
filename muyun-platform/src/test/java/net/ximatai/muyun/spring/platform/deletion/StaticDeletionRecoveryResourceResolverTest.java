package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionRecoveryAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StaticDeletionRecoveryResourceResolverTest {
    private final Root root = new Root();
    private final SoftResource child = new SoftResource("test.detail", "detail-1");
    private final DeletionEntry rootEntry = entry(root, "root-entry", null);
    private final DeletionEntry childEntry = entry(child, "child-entry", "root-entry");

    @Test
    void indexesOnlyExplicitCascadeOwnershipRecursivelyAndRetainsTheServiceProxy() {
        SoftResource grandchild = new SoftResource("test.nested", "nested-1");
        SoftResource unrelated = new SoftResource("test.unrelated", "unrelated-1");
        own(root, child, true);
        own(child, grandchild, true);
        own(root, unrelated, false);
        ProxyFactory factory = new ProxyFactory(root);
        factory.setProxyTargetClass(true);
        Root proxy = (Root) factory.getProxy();
        var resolver = new StaticDeletionRecoveryResourceResolver(List.of(proxy, root));

        assertThat(resolver.resolve(rootEntry)).containsSame(proxy);
        assertThat(resolver.resolve(childEntry)).containsSame(child);
        assertThat(resolver.resolve(entry(grandchild, "nested-entry", "child-entry"))).containsSame(grandchild);
        assertThat(resolver.resolve(entry(unrelated, "unrelated-entry", null))).isEmpty();
        assertThat(resolver.canPurgeAggregateChild(childEntry, rootEntry)).isTrue();
        assertThat(resolver.canPurgeAggregateChild(childEntry, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"childVersion", "parentVersion", "owner", "tenant", "activeChild", "activeParent"})
    void rejectsChangedRetainedOwnershipAndSourceVersions(String change) {
        own(root, child, true);
        var resolver = new StaticDeletionRecoveryResourceResolver(List.of(root));
        switch (change) {
            case "childVersion" -> child.row.setVersion(4);
            case "parentVersion" -> root.row.setVersion(4);
            case "owner" -> child.row.setRootRecordId("other-parent");
            case "tenant" -> child.row.setTenantId("other-tenant");
            case "activeChild" -> child.row.setDeleted(false);
            case "activeParent" -> root.row.setDeleted(false);
        }
        assertThatThrownBy(() -> resolver.canPurgeAggregateChild(childEntry, rootEntry))
                .isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void deniesSubstitutedSourceAndCurrentRelationshipRemoval() {
        own(root, child, true);
        var resolver = new StaticDeletionRecoveryResourceResolver(List.of(root));
        childEntry.setOperationId("other-source");
        assertThat(resolver.canPurgeAggregateChild(childEntry, rootEntry)).isFalse();
        childEntry.setOperationId("source");
        childEntry.setParentEntryId("other-parent-entry");
        assertThat(resolver.canPurgeAggregateChild(childEntry, rootEntry)).isFalse();
        childEntry.setParentEntryId(rootEntry.getId());
        childEntry.setTenantId("other-tenant");
        assertThat(resolver.canPurgeAggregateChild(childEntry, rootEntry)).isFalse();
        childEntry.setTenantId("tenant");
        root.relations.clear();
        assertThat(resolver.canPurgeAggregateChild(childEntry, rootEntry)).isFalse();
    }

    @Test
    void aliasesAreExplicitAndDuplicateDifferentServicesAreRejected() {
        assertThatThrownBy(() -> new StaticDeletionRecoveryResourceResolver(List.of(root, new Root())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate static deletion recovery");
        Root unnamed = new Root() {
            @Override public String getDeletionEntityAlias() { return null; }
        };
        assertThatThrownBy(() -> new StaticDeletionRecoveryResourceResolver(List.of(unnamed)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stable module and entity aliases");
        var resolver = new StaticDeletionRecoveryResourceResolver(List.of(root));
        rootEntry.setResourceEntityAlias(null);
        assertThat(resolver.resolve(rootEntry)).isEmpty();
    }

    private static void own(SoftResource owner, SoftResource child, boolean cascade) {
        child.row.setRootRecordId(owner.row.getId());
        var relation = new ChildRelation<DeletionOperation, DeletionOperation>("details", child,
                DeletionOperation::setRootRecordId, "rootRecordId", parent -> List.of(), DeletionOperation::getRootRecordId);
        if (cascade) relation.cascadeOnParentUnavailable();
        owner.relations.add(relation);
    }

    private static DeletionEntry entry(SoftResource resource, String id, String parentId) {
        DeletionEntry entry = new DeletionEntry();
        var identity = DeletionResourceIdentity.of(resource);
        entry.setId(id); entry.setOperationId("source"); entry.setParentEntryId(parentId);
        entry.setTenantId("tenant"); entry.setResourceModuleAlias(identity.moduleAlias());
        entry.setResourceEntityAlias(identity.entityAlias()); entry.setResourceRecordId(resource.row.getId());
        entry.setResourceVersion(3);
        entry.setTriggerType(parentId == null ? DeletionEntryTrigger.DIRECT : DeletionEntryTrigger.CASCADE);
        return entry;
    }

    static class SoftResource extends AbstractAbilityService<DeletionOperation>
            implements SoftDeleteAbility<DeletionOperation>, ChildAbility<DeletionOperation>, ChildrenAbility<DeletionOperation> {
        final DeletionOperation row = new DeletionOperation();
        final List<ChildRelation<? extends EntityContract, DeletionOperation>> relations = new ArrayList<>();
        SoftResource(String module, String id) {
            super(module, DeletionOperation.class, new TestMemoryDao<>());
            row.setId(id); row.setVersion(3); row.setTenantId("tenant"); row.setDeleted(true);
        }
        @Override public List<ChildRelation<? extends EntityContract, DeletionOperation>> childRelations() { return relations; }
        @Override public DeletionOperation selectIgnoreSoftDelete(String id) { return row.getId().equals(id) ? row : null; }
    }

    static class Root extends SoftResource implements DeletionRecoveryAbility<DeletionOperation> {
        Root() { super("test.root", "root-1"); }
    }
}
