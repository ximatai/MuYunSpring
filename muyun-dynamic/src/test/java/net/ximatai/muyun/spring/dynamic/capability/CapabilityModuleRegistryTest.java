package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityStandardActionCatalog;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.ability.TreeAbility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CapabilityModuleRegistryTest {
    private final CapabilityModuleRegistry registry = CapabilityModuleRegistry.defaultRegistry();

    private EnableCapabilityModule enable() {
        return registry.require(EntityCapability.ENABLE, EnableCapabilityModule.class);
    }

    private SortCapabilityModule sort() {
        return registry.require(EntityCapability.SORT, SortCapabilityModule.class);
    }

    private TreeCapabilityModule tree() {
        return registry.require(EntityCapability.TREE, TreeCapabilityModule.class);
    }

    private RecycleBinCapabilityModule recycleBin() {
        return registry.require(EntityCapability.RECYCLE_BIN, RecycleBinCapabilityModule.class);
    }

    @Test
    void shouldOwnEnableDefinitionAndStandardActionsAsOneTypedModule() {
        EntityDefinition entity = entity(Set.of(EntityCapability.CRUD, EntityCapability.ENABLE), FieldDefinition.enabled());

        registry.validate(entity);

        assertThat(enable().actions().standardActions())
                .containsExactly(PlatformAction.ENABLE, PlatformAction.DISABLE);
        assertThat(registry.find(EntityCapability.ENABLE)).containsSame(enable());
        assertThat(registry.actionOwner(PlatformAction.ENABLE)).containsSame(enable().actions());
        assertThat(registry.actionOwner(PlatformAction.ENABLE).orElseThrow()
                .endpointProjection(PlatformAction.ENABLE).orElseThrow().path()).isEqualTo("/enable/{id}");
        assertThat(registry.actionOwner(PlatformAction.DISABLE).orElseThrow()
                .endpointProjection(PlatformAction.DISABLE).orElseThrow().path()).isEqualTo("/disable/{id}");
        assertThat(registry.actionOwner(PlatformAction.ENABLE).orElseThrow()
                .webActionContract(PlatformAction.ENABLE, false).orElseThrow())
                .extracting(CapabilityActionContribution.CapabilityWebActionContract::requestBody,
                        CapabilityActionContribution.CapabilityWebActionContract::openApiRequestSchema,
                        CapabilityActionContribution.CapabilityWebActionContract::openApiResponseSchema)
                .containsExactly(CapabilityActionContribution.CapabilityWebRequestBody.RECORD_ACTION,
                        "RecordActionWebRequest", "integer");
    }

    @Test
    void shouldRejectEnableWithoutItsOwnedDynamicField() {
        EntityDefinition entity = entity(Set.of(EntityCapability.CRUD, EntityCapability.ENABLE), FieldDefinition.string("code", "Code"));

        assertThatThrownBy(() -> registry.validate(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("ENABLE capability requires standard field enabled");
    }

    @Test
    void shouldOwnSortDefinitionAndEndpointProjectionAsOneTypedModule() {
        EntityDefinition entity = entity(Set.of(EntityCapability.CRUD, EntityCapability.SORT), FieldDefinition.sortOrder());

        registry.validate(entity);

        assertThat(sort().dependencies()).containsExactly(EntityCapability.CRUD);
        assertThat(sort().actions().standardActions()).containsExactly(PlatformAction.SORT);
        assertThat(registry.actionOwner(PlatformAction.SORT)).containsSame(sort().actions());
        assertThat(registry.actionOwner(PlatformAction.SORT).orElseThrow()
                .endpointProjection(PlatformAction.SORT).orElseThrow().path()).isEqualTo("/sort/{id}");
    }

    @Test
    void shouldRejectSortWithoutTheOwnedStandardField() {
        EntityDefinition entity = entity(Set.of(EntityCapability.CRUD, EntityCapability.SORT),
                FieldDefinition.integer("rank", "Rank").sortable());

        assertThatThrownBy(() -> registry.validate(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("SORT capability requires standard field sortOrder/sort_order");
    }

    @Test
    void shouldRejectSortFieldAndPartitionDeclarationsWhenSortIsNotEnabled() {
        EntityDefinition sortable = entity(Set.of(EntityCapability.CRUD), FieldDefinition.sortOrder());
        EntityDefinition partitioned = new EntityDefinition("contract", "contract", "Contract",
                List.of(FieldDefinition.string("organizationId", "Organization")), Set.of(EntityCapability.CRUD))
                .withSortPartitionFields("organizationId");

        assertThatThrownBy(() -> registry.validate(sortable))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("sortable field requires SORT capability");
        assertThatThrownBy(() -> registry.validate(partitioned))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("sort partition requires SORT capability");
    }

    @Test
    void shouldOwnTreeParentContractAndDeclareItsSortDependency() {
        EntityDefinition entity = new EntityDefinition("contract", "contract", "Contract",
                List.of(FieldDefinition.parentId(), FieldDefinition.sortOrder()),
                Set.of(EntityCapability.CRUD, EntityCapability.TREE, EntityCapability.SORT));

        registry.validate(entity);

        assertThat(tree().dependencies()).containsExactlyInAnyOrder(EntityCapability.CRUD, EntityCapability.SORT);
        assertThat(tree().actions().standardActions()).containsExactly(PlatformAction.TREE);
        assertThat(tree().actions().enabledOnDynamicCapabilities(Set.of(EntityCapability.TREE.name()))).isTrue();
        assertThat(tree().actions().enabledOnDynamicCapabilities(Set.of(EntityCapability.SORT.name()))).isFalse();
        assertThat(registry.actionOwner(PlatformAction.TREE)).containsSame(tree().actions());
        assertThat(tree().actions().staticOperations())
                .extracting(operation -> operation.operationCode())
                .containsExactly("tree", "treeQuery", "subtree", "sort");
    }

    @Test
    void shouldOwnRecycleBinLifecycleActionsAndEndpointFactsAsOneTypedModule() {
        EntityDefinition entity = entity(Set.of(EntityCapability.CRUD, EntityCapability.RECYCLE_BIN),
                FieldDefinition.string("code", "Code"));

        registry.validate(entity);

        assertThat(recycleBin().dependencies()).containsExactly(EntityCapability.CRUD);
        assertThat(recycleBin().actions().standardActions()).containsExactly(
                PlatformAction.RECYCLE_BIN_QUERY, PlatformAction.RECYCLE_BIN_RESTORE, PlatformAction.RECYCLE_BIN_PURGE);
        assertThat(registry.actionOwner(PlatformAction.RECYCLE_BIN_RESTORE)).containsSame(recycleBin().actions());
        assertThat(recycleBin().actions().staticOperations(false)).extracting(operation -> operation.operationCode())
                .containsExactly("query", "view", "restore");
        assertThat(recycleBin().actions().staticOperations(true)).extracting(operation -> operation.operationCode())
                .containsExactly("query", "view", "restore", "purge");
        assertThat(recycleBin().actions().dynamicHttpEndpoints()).extracting(endpoint -> endpoint.endpoint().path())
                .containsExactly("/recycle-bin/query", "/recycle-bin/view/{id}",
                        "/recycle-bin/{sourceDeleteOperationId}/restore",
                        "/recycle-bin/{sourceDeleteOperationId}/purge");
        assertThat(recycleBin().actions().dynamicHttpEndpoints())
                .extracting(CapabilityActionContribution.CapabilityHttpEndpointContract::openApiResponseSchema)
                .containsExactly("RecycleBinItemPage", "RecycleBinItem", "RestoreReport", "PurgeReport");
        assertThat(recycleBin().actions().isHttpOnlyDynamicAction(PlatformAction.RECYCLE_BIN_RESTORE)).isTrue();
    }

    @Test
    void shouldRejectTreeWithoutItsOwnedParentField() {
        EntityDefinition entity = new EntityDefinition("contract", "contract", "Contract",
                List.of(FieldDefinition.sortOrder()), Set.of(EntityCapability.CRUD, EntityCapability.TREE));

        assertThatThrownBy(() -> registry.validate(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("TREE capability requires standard field parentId");
    }

    @Test
    void shouldPublishDependencyAndRejectDuplicateModuleRegistration() {
        assertThat(enable().dependencies()).containsExactly(EntityCapability.CRUD);
        assertThatIllegalStateException().isThrownBy(() -> new CapabilityModuleRegistry(List.of(
                new EnableCapabilityModule(), new EnableCapabilityModule())))
                .withMessageContaining("duplicate capability module registration");
    }

    @Test
    void shouldComposeSourceRuntimeFacetsFromActionOwnersAndKeepTheTreeSortBridgeExplicit() {
        assertThat(registry.modules())
                .allSatisfy(module -> assertThat(module.actionContribution().staticRuntimeHandler()).isPresent());
        assertThat(enable().actions().dynamicRuntimeHandler()).isPresent();
        assertThat(sort().actions().dynamicRuntimeHandler()).isPresent();
        assertThat(tree().actions().dynamicRuntimeHandler()).isEmpty();
        assertThat(recycleBin().actions().dynamicRuntimeHandler()).isEmpty();

        assertThat(registry.staticActionOwner(PlatformAction.SORT, mock(TreeAbility.class)))
                .containsSame(tree().actions());
        assertThat(registry.staticActionOwner(PlatformAction.SORT, new Object()))
                .containsSame(sort().actions());
    }

    @Test
    void shouldNotContributeOrDispatchEnableActionsWhenCapabilityIsAbsent() {
        EntityDefinition entity = entity(Set.of(EntityCapability.CRUD), FieldDefinition.string("code", "Code"));

        assertThat(EntityStandardActionCatalog.from(entity))
                .extracting(action -> action.actionCode())
                .doesNotContain(PlatformAction.ENABLE.code(), PlatformAction.DISABLE.code());
        assertThat(registry.actionOwner(PlatformAction.CREATE)).isEmpty();
        assertThat(enable().actions().endpointProjection(PlatformAction.CREATE)).isEmpty();
    }

    private EntityDefinition entity(Set<EntityCapability> capabilities, FieldDefinition field) {
        return new EntityDefinition("contract", "contract", "Contract", List.of(field), capabilities);
    }
}
