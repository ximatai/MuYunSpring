package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AggregateChildRelationExpansionGatewayTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldReadOnlyTheDeclaredChildRowsAndProjectOnlyExpansionFields() {
        FieldUiControlService parentService = mock(FieldUiControlService.class,
                org.mockito.Mockito.withSettings().extraInterfaces(ChildrenAbility.class));
        ChildrenAbility<FieldUiControl> childrenAbility = (ChildrenAbility<FieldUiControl>) (Object) parentService;
        FieldUiControlPropertyService childService = mock(FieldUiControlPropertyService.class);
        FieldUiControl parent = new FieldUiControl();
        parent.setId("control-1");
        FieldUiControlProperty child = new FieldUiControlProperty();
        child.setId("property-1");
        child.setFieldUiControlAlias("control-1");
        child.setAttributeAlias("placeholder");
        when(parentService.select("control-1")).thenReturn(parent);
        when(childService.selectChildRows(any())).thenReturn(List.of(child));
        ChildRelation<FieldUiControlProperty, FieldUiControl> relation = new ChildRelation<>(
                "properties", childService, FieldUiControlProperty::setFieldUiControlAlias,
                "fieldUiControlAlias", FieldUiControl::getProperties);
        when(childrenAbility.childRelations()).thenReturn((List) List.of(relation));

        ModuleExecutionPlanCatalog catalog = mock(ModuleExecutionPlanCatalog.class);
        ModuleExecutionPlan plan = mock(ModuleExecutionPlan.class);
        ResolvedModuleUiDescriptor descriptor = mock(ResolvedModuleUiDescriptor.class);
        ResolvedModulePageDescriptor page = mock(ResolvedModulePageDescriptor.class);
        ResolvedPageListDescriptor list = mock(ResolvedPageListDescriptor.class);
        ResolvedDetailRelationDescriptor descriptorRelation = mock(ResolvedDetailRelationDescriptor.class);
        when(catalog.find(FieldUiControlService.MODULE_ALIAS)).thenReturn(Optional.of(plan));
        when(plan.uiDescriptor()).thenReturn(descriptor);
        when(descriptor.page()).thenReturn(page);
        when(page.list()).thenReturn(list);
        when(list.relationExpansions()).thenReturn(List.of(
                new ResolvedPageListRelationExpansionDescriptor("properties", List.of("attributeAlias"))));
        when(descriptor.detailRelations()).thenReturn(List.of(descriptorRelation));
        when(descriptorRelation.code()).thenReturn("properties");
        when(descriptorRelation.embeddedField()).thenReturn("properties");
        when(descriptorRelation.targetModuleAlias()).thenReturn(FieldUiControlPropertyService.MODULE_ALIAS);

        var response = new AggregateChildRelationExpansionGateway(catalog).read(
                FieldUiControlService.MODULE_ALIAS, parentService, "control-1", "properties");

        assertThat(response.records()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", "property-1")
                    .containsEntry("version", 0)
                    .containsEntry("attributeAlias", "placeholder")
                    .doesNotContainKey("fieldUiControlAlias");
        });
        verify(parentService).select("control-1");
        verify(childService).selectChildRows(any());
    }
}
