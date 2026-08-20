package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationMutationContract;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationQueryContract;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ManagedDetailRelationGatewayWebTest {
    @Test
    void shouldRunCompiledManagedRelationThroughParentHttpBoundary() throws Exception {
        FieldUiControlService parentService = mock(FieldUiControlService.class);
        FieldUiControlPropertyService childService = mock(FieldUiControlPropertyService.class);
        FieldUiControl parent = parent("select");
        FieldUiControlProperty property = property("property-1", "select");
        property.setTitle("Original title");
        when(parentService.select("select")).thenReturn(parent);
        when(childService.pageQuery(any(Criteria.class), any(PageRequest.class), any(net.ximatai.muyun.database.core.orm.Sort[].class))).thenReturn(
                PageResult.of(List.of(property), 1, PageRequest.of(1, 20)));
        when(childService.insert(any(FieldUiControlProperty.class))).thenReturn("property-2");
        when(childService.select("property-2")).thenReturn(property("property-2", "select"));
        when(childService.select("property-1")).thenReturn(property);

        ActionExecutionPolicyService policy = mock(ActionExecutionPolicyService.class);
        MockMvc mvc = mvc(parentService, gateway(parentService, childService, mutation(true, true, true), policy), policy);
        String base = "/platform.field_ui_control/view/select/relations/properties";

        mvc.perform(post(base + "/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].fieldUiControlAlias").value("select"));
        mvc.perform(post(base + "/insert").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldUiControlAlias\":\"forged\",\"tenantId\":\"forged\",\"deleted\":true,\"attributeAlias\":\"placeholder\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldUiControlAlias").value("select"));
        mvc.perform(post(base + "/update/property-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldUiControlAlias\":\"forged\",\"tenantId\":\"forged\",\"title\":\"Changed title\",\"version\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post(base + "/delete/property-1").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<FieldUiControlProperty> inserted = org.mockito.ArgumentCaptor.forClass(FieldUiControlProperty.class);
        verify(childService).insert(inserted.capture());
        assertThat(inserted.getValue().getFieldUiControlAlias()).isEqualTo("select");
        assertThat(inserted.getValue().getTenantId()).isNull();
        org.mockito.ArgumentCaptor<FieldUiControlProperty> updated = org.mockito.ArgumentCaptor.forClass(FieldUiControlProperty.class);
        verify(childService).update(updated.capture());
        assertThat(updated.getValue().getAttributeAlias()).isEqualTo("placeholder");
        assertThat(updated.getValue().getTitle()).isEqualTo("Changed title");
        assertThat(updated.getValue().getTenantId()).isNull();
        verify(childService).delete(eq("property-1"), any());
        org.mockito.ArgumentCaptor<ActionExecutionContext> authorization =
                org.mockito.ArgumentCaptor.forClass(ActionExecutionContext.class);
        verify(policy, org.mockito.Mockito.atLeast(4)).requireAuthorized(authorization.capture());
        assertThat(authorization.getAllValues()).extracting(ActionExecutionContext::actionCode)
                .contains("field_ui_control_property_query", "field_ui_control_property_create",
                        "field_ui_control_property_update", "field_ui_control_property_delete");
    }

    @Test
    void shouldRejectReadOnlyCrossParentAndUnauthorizedManagedMutations() throws Exception {
        FieldUiControlService parentService = mock(FieldUiControlService.class);
        FieldUiControlPropertyService childService = mock(FieldUiControlPropertyService.class);
        when(parentService.select("select")).thenReturn(parent("select"));
        when(childService.select("property-1")).thenReturn(property("property-1", "other"));
        ManagedDetailRelationGateway readOnly = gateway(parentService, childService, mutation(false, false, false),
                new AllowAllPolicyService());
        assertThatThrownBy(() -> readOnly.insert(FieldUiControlService.MODULE_ALIAS, parentService, "select",
                "properties", Map.of("attributeAlias", "placeholder")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mutation is not declared");

        ManagedDetailRelationGateway crossParent = gateway(parentService, childService, mutation(true, true, true),
                new AllowAllPolicyService());
        assertThatThrownBy(() -> crossParent.update(FieldUiControlService.MODULE_ALIAS, parentService, "select",
                "properties", "property-1", Map.of("attributeAlias", "placeholder")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not belong");
        assertThatThrownBy(() -> crossParent.delete(FieldUiControlService.MODULE_ALIAS, parentService, "select",
                "properties", "property-1", new net.ximatai.muyun.spring.web.RecordActionWebRequest(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not belong");

        ActionExecutionPolicyService denied = context -> {
            if ("field_ui_control_property_create".equals(context.actionCode())) {
                throw new SecurityException("child update denied");
            }
        };
        ManagedDetailRelationGateway deniedGateway = gateway(parentService, childService, mutation(true, true, true), denied);
        assertThatThrownBy(() -> deniedGateway.insert(FieldUiControlService.MODULE_ALIAS, parentService, "select",
                "properties", Map.of("attributeAlias", "placeholder")))
                .isInstanceOf(SecurityException.class).hasMessageContaining("child update denied");
        assertThatThrownBy(() -> mvc(parentService, deniedGateway, denied).perform(post(
                        "/platform.field_ui_control/view/select/relations/properties/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributeAlias\":\"placeholder\"}")))
                .hasRootCauseInstanceOf(SecurityException.class)
                .hasRootCauseMessage("child update denied");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolvePersistedParentWithViewForQueryAndUpdateForEveryMutation() {
        FieldUiControlService parentService = mock(FieldUiControlService.class,
                org.mockito.Mockito.withSettings().extraInterfaces(DataScopeAbility.class));
        DataScopeAbility<FieldUiControl> scoped = (DataScopeAbility<FieldUiControl>) (Object) parentService;
        FieldUiControlPropertyService childService = mock(FieldUiControlPropertyService.class);
        when(scoped.selectForAction(PlatformAction.VIEW, "select")).thenReturn(parent("select"));
        when(scoped.selectForAction(PlatformAction.UPDATE, "select")).thenReturn(null);
        when(childService.pageQuery(any(Criteria.class), any(PageRequest.class),
                any(net.ximatai.muyun.database.core.orm.Sort[].class)))
                .thenReturn(PageResult.of(List.of(), 0, PageRequest.of(1, 20)));
        ManagedDetailRelationGateway gateway = gateway(parentService, childService,
                mutation(true, true, true), new AllowAllPolicyService());

        gateway.query(FieldUiControlService.MODULE_ALIAS, parentService, "select", "properties", null);
        assertThatThrownBy(() -> gateway.insert(FieldUiControlService.MODULE_ALIAS, parentService, "select",
                "properties", Map.of("attributeAlias", "placeholder")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent is not visible");

        verify(scoped).selectForAction(PlatformAction.VIEW, "select");
        verify(scoped).selectForAction(PlatformAction.UPDATE, "select");
    }

    private static MockMvc mvc(FieldUiControlService parentService, ManagedDetailRelationGateway gateway,
                               ActionExecutionPolicyService policy) {
        FieldUiControlWebController controller = new FieldUiControlWebController();
        ReflectionTestUtils.setField(controller, "service", parentService);
        ReflectionTestUtils.setField(controller, "managedDetailRelationGateway", gateway);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ActionEndpointInterceptor(policy, new ActionEndpointContextResolver()))
                .build();
    }

    private static ManagedDetailRelationGateway gateway(FieldUiControlService parentService,
                                                        FieldUiControlPropertyService childService,
                                                        ResolvedDetailRelationMutationContract mutation,
                                                        ActionExecutionPolicyService policy) {
        ModuleExecutionPlanCatalog catalog = mock(ModuleExecutionPlanCatalog.class);
        ModuleExecutionPlan plan = mock(ModuleExecutionPlan.class);
        ResolvedModuleUiDescriptor descriptor = mock(ResolvedModuleUiDescriptor.class);
        when(catalog.find(FieldUiControlService.MODULE_ALIAS)).thenReturn(Optional.of(plan));
        when(plan.uiDescriptor()).thenReturn(descriptor);
        when(descriptor.detailRelations()).thenReturn(List.of(new ResolvedDetailRelationDescriptor(
                "properties", "控件属性", false, FieldUiControlService.MODULE_ALIAS, "field_ui_control",
                FieldUiControlPropertyService.MODULE_ALIAS, "field_ui_control_property", "fieldUiControlAlias",
                new ResolvedDetailRelationQueryContract(null, true, false, null,
                        net.ximatai.muyun.spring.ability.query.QuerySchema.from(
                                net.ximatai.muyun.spring.ability.query.QueryDescriptor.builder("field_ui_control_property").build()),
                        "field_ui_control_property_query"),
                mutation, true)));
        ResolvedPageDetailEditorContribution contribution = mock(ResolvedPageDetailEditorContribution.class);
        ResolvedViewDescriptor editor = mock(ResolvedViewDescriptor.class);
        ResolvedViewFieldDescriptor field = mock(ResolvedViewFieldDescriptor.class);
        ResolvedViewFieldDescriptor titleField = mock(ResolvedViewFieldDescriptor.class);
        ViewFieldRef fieldRef = mock(ViewFieldRef.class);
        ViewFieldRef titleFieldRef = mock(ViewFieldRef.class);
        when(descriptor.editorContributions()).thenReturn(List.of(contribution));
        when(contribution.resource()).thenReturn("field_ui_control_property");
        when(contribution.editor()).thenReturn(editor);
        when(editor.fields()).thenReturn(List.of(field, titleField));
        when(field.fieldRef()).thenReturn(fieldRef);
        when(fieldRef.fieldName()).thenReturn("attributeAlias");
        when(titleField.fieldRef()).thenReturn(titleFieldRef);
        when(titleFieldRef.fieldName()).thenReturn("title");
        StaticManagedDetailRelationHandler<FieldUiControl, FieldUiControlProperty> handler =
                new StaticManagedDetailRelationHandler<>() {
                    public String parentModuleAlias() { return FieldUiControlService.MODULE_ALIAS; }
                    public String relationCode() { return "properties"; }
                    public String parentBinding() { return "fieldUiControlAlias"; }
                    public String childEntityAlias() { return "field_ui_control_property"; }
                    public Class<FieldUiControlProperty> childModelClass() { return FieldUiControlProperty.class; }
                    public FieldUiControlPropertyService childService() { return childService; }
                    public Criteria criteriaFor(FieldUiControl parent) { return Criteria.of().eq("fieldUiControlAlias", parent.getAlias()); }
                    public void bindParent(FieldUiControlProperty child, FieldUiControl parent) { child.setFieldUiControlAlias(parent.getAlias()); }
                    public boolean belongsTo(FieldUiControlProperty child, FieldUiControl parent) { return parent.getAlias().equals(child.getFieldUiControlAlias()); }
                };
        return new ManagedDetailRelationGateway(catalog, policy, new ObjectMapper(), List.of(handler));
    }

    private static ResolvedDetailRelationMutationContract mutation(boolean create, boolean update, boolean delete) {
        return new ResolvedDetailRelationMutationContract(create, update, delete,
                create ? "field_ui_control_property_create" : null,
                update ? "field_ui_control_property_update" : null,
                delete ? "field_ui_control_property_delete" : null);
    }

    private static FieldUiControl parent(String alias) {
        FieldUiControl control = new FieldUiControl();
        control.setId(alias);
        control.setAlias(alias);
        return control;
    }

    private static FieldUiControlProperty property(String id, String parentAlias) {
        FieldUiControlProperty property = new FieldUiControlProperty();
        property.setId(id);
        property.setFieldUiControlAlias(parentAlias);
        property.setAttributeAlias("placeholder");
        return property;
    }

    private static final class AllowAllPolicyService implements ActionExecutionPolicyService {
        @Override
        public void requireAuthorized(net.ximatai.muyun.spring.common.platform.ActionExecutionContext context) { }
    }
}
