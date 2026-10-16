package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessContentCapabilityModuleTest {
    @Test void staticFieldsDeclareTheCapabilityWithoutAServiceMixinOrGeneratedBusinessMutation() {
        var module = new BusinessContentCapabilityModule();
        var facet = module.staticFacet().orElseThrow();
        assertThat(facet.supports(new TextService())).isTrue();
        assertThat(facet.supports(new PlainService())).isFalse();
        assertThat(facet.supports(new ParentService())).isTrue();
        assertThat(facet.standardActions(new net.ximatai.muyun.spring.ability.capability.StaticCapabilityOperationContext(
                new TextService(), Set.of(EntityCapability.CRUD, EntityCapability.BUSINESS_CONTENT), Map.of())))
                .containsExactly(PlatformAction.CONTENT_EDIT, PlatformAction.CONTENT_CONFIRM);
        assertThat(facet.standardOperations(null)).isEmpty();
        assertThat(module.staticRuntimeHandler()).isEmpty();
    }
    @Test void dynamicFieldsAndOwnedChildrenPublishIndependentMaintenanceRightsWithoutAddingStoredFields() {
        var parent = new EntityDefinition("main", "main", "Main", List.of(FieldDefinition.string("code", "Code")));
        var child = new EntityDefinition("line", "line", "Line", List.of(
                FieldDefinition.string("mainId", "Parent"), FieldDefinition.text("description", "Description")
                        .content(new ContentFieldDefinition("stable", ContentPolicy.AUTO_TRANSLATE))));
        assertThat(parent.supports(EntityCapability.BUSINESS_CONTENT)).isFalse();
        assertThat(child.supports(EntityCapability.BUSINESS_CONTENT)).isTrue();
        var definition = ModuleDefinition.builder("test.content", "Content").entities(List.of(parent, child))
                .relations(List.of(EntityRelationDefinition.child("lines", "main", "line", "mainId"))).build();
        var aggregate = definition.entities().getFirst();
        assertThat(aggregate.supports(EntityCapability.BUSINESS_CONTENT)).isTrue();
        assertThat(aggregate.fields()).isEqualTo(parent.fields());
        var actions = EntityStandardActionCatalog.from(aggregate).stream()
                .filter(action -> action.actionCode().startsWith("content")).toList();
        assertThat(actions).extracting(EntityActionDefinition::actionCode).containsExactly("contentEdit", "contentConfirm");
        assertThat(actions).allSatisfy(action -> {
            assertThat(action.actionAuth()).isTrue(); assertThat(action.authInheritActionCode()).isNull();
            assertThat(action.dataAuth()).isFalse();
        });
        assertThat(EntityStandardActionCatalog.from(parent)).extracting(EntityActionDefinition::actionCode)
                .doesNotContain("contentEdit", "contentConfirm");
    }
    @Test void aBareCapabilityFlagCannotInventContentMaintenanceWithoutDeclaredBusinessText() {
        var entity = new EntityDefinition("main", "plain_content", "Plain", List.of(FieldDefinition.string("code", "Code")),
                Set.of(EntityCapability.BUSINESS_CONTENT));
        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(new ModuleDefinition("test.plain", "Plain", List.of(entity))))
                .isInstanceOf(ModuleDefinitionException.class).hasMessageContaining("BUSINESS_CONTENT requires declared business text");
    }
    static class TextRow extends StandardEntity {
        @BusinessContent(value = ContentPolicy.MANUAL_TRANSLATIONS, key = "name")
        @Column(name = "name", type = ColumnType.TEXT) private String name;
    }
    static class TextService extends AbstractAbilityService<TextRow> {
        @SuppressWarnings("unchecked") TextService() { super("test.content", TextRow.class, mock(BaseDao.class)); }
    }
    static class Parent extends StandardEntity {
        @net.ximatai.muyun.spring.ability.child.Children(relationCode = "lines") private List<TextChild> lines;
    }
    static class TextChild extends TextRow {
        @net.ximatai.muyun.spring.ability.child.ChildOf
        @net.ximatai.muyun.spring.ability.reference.ReferenceTo(moduleAlias = "test.content", entityAlias = "parent")
        @Column(name = "parent_id", type = ColumnType.VARCHAR, length = 64) private String parentId;
    }
    static class ParentService extends AbstractAbilityService<Parent> implements net.ximatai.muyun.spring.ability.child.ChildrenAbility<Parent> {
        @SuppressWarnings("unchecked") ParentService() { super("test.parent", Parent.class, mock(BaseDao.class)); }
    }
    static class PlainService extends AbstractAbilityService<StandardEntity> {
        @SuppressWarnings("unchecked") PlainService() { super("test.plain", StandardEntity.class, mock(BaseDao.class)); }
    }
}
