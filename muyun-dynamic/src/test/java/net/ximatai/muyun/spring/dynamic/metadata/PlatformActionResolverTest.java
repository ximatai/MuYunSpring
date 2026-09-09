package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformActionResolverTest {
    @Test
    void shouldDeriveStandardActionsFromEntityCapabilities() {
        EntityDefinition entity = new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code"),
                FieldDefinition.titleField().queryable(),
                FieldDefinition.parentId(),
                FieldDefinition.sortOrder(),
                FieldDefinition.enabled()
        ), Set.of(EntityCapability.TREE, EntityCapability.REFERENCE, EntityCapability.ENABLE,
                EntityCapability.EXCHANGE, EntityCapability.RECYCLE_BIN, EntityCapability.DATA_SCOPE));

        assertThat(EntityStandardActionCatalog.from(entity))
                .extracting(EntityActionDefinition::actionCode)
                .containsExactlyElementsOf(Arrays.stream(PlatformAction.values())
                        .map(PlatformAction::code)
                        .toList());
    }

    @Test
    void shouldExposeStandardActionsFromSupportedActionGroupsOnly() {
        assertThat(actionCodes(entityWith(EntityCapability.CRUD)))
                .containsExactlyElementsOf(actionCodes(PlatformActionGroup.MENU, PlatformActionGroup.CRUD));
        assertThat(actionCodes(entityWith(EntityCapability.SORT)))
                .containsExactlyElementsOf(actionCodes(PlatformActionGroup.MENU, PlatformActionGroup.CRUD,
                        PlatformActionGroup.SORT));
        assertThat(actionCodes(entityWith(EntityCapability.TREE)))
                .containsExactlyElementsOf(actionCodes(PlatformActionGroup.MENU, PlatformActionGroup.CRUD,
                        PlatformActionGroup.SORT, PlatformActionGroup.TREE));
        assertThat(actionCodes(entityWith(EntityCapability.REFERENCE)))
                .containsExactlyElementsOf(actionCodes(PlatformActionGroup.MENU, PlatformActionGroup.CRUD,
                        PlatformActionGroup.REFERENCE));
        assertThat(actionCodes(entityWith(EntityCapability.ENABLE)))
                .containsExactlyElementsOf(actionCodes(PlatformActionGroup.MENU, PlatformActionGroup.CRUD,
                        PlatformActionGroup.ENABLE));
        assertThat(actionCodes(entityWith(EntityCapability.EXCHANGE)))
                .containsExactlyElementsOf(actionCodes(PlatformActionGroup.MENU, PlatformActionGroup.CRUD,
                        PlatformActionGroup.EXCHANGE));
        assertThat(actionCodes(entityWith(EntityCapability.RECYCLE_BIN)))
                .containsExactlyElementsOf(actionCodes(PlatformActionGroup.MENU, PlatformActionGroup.CRUD,
                        PlatformActionGroup.RECYCLE_BIN));
    }

    @Test
    void shouldDeriveStandardActionPermissionPolicyFromPlatformActionAndEntityCapabilities() {
        List<EntityActionDefinition> plainActions = EntityStandardActionCatalog.from(entityWith(EntityCapability.CRUD));
        List<EntityActionDefinition> dataScopedActions =
                EntityStandardActionCatalog.from(entityWith(EntityCapability.DATA_SCOPE));
        List<EntityActionDefinition> allCapabilityActions =
                EntityStandardActionCatalog.from(entityWith(EntityCapability.DATA_SCOPE, EntityCapability.TREE,
                        EntityCapability.REFERENCE, EntityCapability.ENABLE, EntityCapability.EXCHANGE));

        assertThat(action(plainActions, PlatformAction.MENU).actionAuth()).isTrue();
        assertThat(action(plainActions, PlatformAction.MENU).dataAuth()).isFalse();
        assertThat(action(plainActions, PlatformAction.UPDATE).actionAuth()).isTrue();
        assertThat(action(plainActions, PlatformAction.UPDATE).dataAuth()).isFalse();
        assertThat(action(dataScopedActions, PlatformAction.CREATE).dataAuth()).isFalse();
        assertThat(action(dataScopedActions, PlatformAction.UPDATE).dataAuth()).isTrue();
        assertThat(action(dataScopedActions, PlatformAction.DELETE).dataAuth()).isTrue();
        assertThat(action(dataScopedActions, PlatformAction.BATCH_DELETE).dataAuth()).isTrue();
        assertThat(action(dataScopedActions, PlatformAction.BATCH_DELETE).level()).isEqualTo(EntityActionLevel.BATCH);
        assertThat(action(dataScopedActions, PlatformAction.BATCH_DELETE).authInheritActionCode()).isEqualTo("delete");
        assertThat(action(dataScopedActions, PlatformAction.QUERY).dataAuth()).isTrue();
        assertThat(action(dataScopedActions, PlatformAction.QUERY).authInheritActionCode()).isEqualTo("view");
        assertThat(action(EntityStandardActionCatalog.from(entityWith(EntityCapability.EXCHANGE)),
                PlatformAction.EXPORT).dataAuth()).isTrue();
        assertThat(action(EntityStandardActionCatalog.from(entityWith(EntityCapability.EXCHANGE)),
                PlatformAction.EXPORT).authInheritActionCode()).isEqualTo("view");
        assertThat(action(allCapabilityActions, PlatformAction.TREE).authInheritActionCode()).isEqualTo("view");
        assertThat(action(allCapabilityActions, PlatformAction.REFERENCE).authInheritActionCode()).isEqualTo("view");
        assertThat(action(allCapabilityActions, PlatformAction.EXPORT).dataAuth()).isTrue();
        assertThat(action(allCapabilityActions, PlatformAction.EXPORT).authInheritActionCode()).isEqualTo("view");
        assertThat(action(allCapabilityActions, PlatformAction.DISABLE).authInheritActionCode()).isEqualTo("enable");
    }

    private EntityDefinition entityWith(EntityCapability capability) {
        return entityWith(capability, new EntityCapability[0]);
    }

    private EntityDefinition entityWith(EntityCapability capability, EntityCapability... capabilities) {
        Set<EntityCapability> values = new java.util.LinkedHashSet<>();
        values.add(capability);
        values.addAll(List.of(capabilities));
        return new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code"),
                FieldDefinition.titleField(),
                FieldDefinition.parentId(),
                FieldDefinition.sortOrder(),
                FieldDefinition.enabled()
        )).withCapabilities(values.toArray(EntityCapability[]::new));
    }

    private List<String> actionCodes(EntityDefinition entity) {
        return EntityStandardActionCatalog.from(entity).stream()
                .map(EntityActionDefinition::actionCode)
                .toList();
    }

    private List<String> actionCodes(PlatformActionGroup firstGroup, PlatformActionGroup... otherGroups) {
        List<PlatformActionGroup> groups = new ArrayList<>();
        groups.add(firstGroup);
        groups.addAll(List.of(otherGroups));
        return Arrays.stream(PlatformAction.values())
                .filter(action -> groups.contains(action.group()))
                .map(PlatformAction::code)
                .toList();
    }

    private EntityActionDefinition action(List<EntityActionDefinition> actions, PlatformAction action) {
        return actions.stream()
                .filter(item -> item.actionCode().equals(action.code()))
                .findFirst()
                .orElseThrow();
    }
}
