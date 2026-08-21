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
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.DataScopeFieldMapping;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationMutationContract;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationQueryContract;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationEditing;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import net.ximatai.muyun.spring.web.ActionResultResponseAdvice;
import net.ximatai.muyun.spring.web.BusinessMutationInterceptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
    void shouldExposeRetainedAggregateChildrenOnlyThroughTheParentRelationBoundary() throws Exception {
        FieldUiControlService parentService = mock(FieldUiControlService.class);
        ManagedDetailRelationGateway gateway = mock(ManagedDetailRelationGateway.class);
        FieldUiControlProperty retained = property("retained-1", "select");
        retained.setDeleted(Boolean.TRUE);
        org.mockito.Mockito.doReturn(net.ximatai.muyun.spring.web.WebPageResponse.fromList(List.of(retained)))
                .when(gateway).queryRecycleBin(FieldUiControlService.MODULE_ALIAS, parentService,
                        "select", "properties");

        mvc(parentService, gateway, new AllowAllPolicyService()).perform(post(
                        "/platform.field_ui_control/view/select/relations/properties/recycle-bin/query")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("retained-1"))
                .andExpect(jsonPath("$.records[0].fieldUiControlAlias").value("select"));

        verify(gateway).queryRecycleBin(FieldUiControlService.MODULE_ALIAS, parentService,
                "select", "properties");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldQueryOnlyTheDeclaredAggregateChildRelationRecycleBin() {
        FieldUiControlService parentService = mock(FieldUiControlService.class,
                org.mockito.Mockito.withSettings().extraInterfaces(ChildrenAbility.class));
        ChildrenAbility<FieldUiControl> children = (ChildrenAbility<FieldUiControl>) (Object) parentService;
        FieldUiControlPropertyService childService = mock(FieldUiControlPropertyService.class);
        FieldUiControl parent = parent("select");
        FieldUiControlProperty retained = property("retained-1", "select");
        retained.setDeleted(Boolean.TRUE);
        when(parentService.select("select")).thenReturn(parent);
        when(childService.selectDeletedChildRows(any(Criteria.class))).thenReturn(List.of(retained));
        ChildRelation<FieldUiControlProperty, FieldUiControl> childRelation = new ChildRelation<>(
                "properties", childService, FieldUiControlProperty::setFieldUiControlAlias,
                "fieldUiControlAlias", FieldUiControl::getProperties);
        when(children.childRelations()).thenReturn((List) List.of(childRelation));

        ModuleExecutionPlanCatalog catalog = mock(ModuleExecutionPlanCatalog.class);
        ModuleExecutionPlan plan = mock(ModuleExecutionPlan.class);
        ResolvedModuleUiDescriptor descriptor = mock(ResolvedModuleUiDescriptor.class);
        ResolvedDetailRelationDescriptor relation = mock(ResolvedDetailRelationDescriptor.class);
        when(catalog.find(FieldUiControlService.MODULE_ALIAS)).thenReturn(Optional.of(plan));
        when(plan.uiDescriptor()).thenReturn(descriptor);
        when(plan.actions()).thenReturn(List.of(StaticModuleActionDefinition.platformAction(PlatformAction.UPDATE)));
        when(descriptor.detailRelations()).thenReturn(List.of(relation));
        when(relation.code()).thenReturn("properties");
        when(relation.embeddedField()).thenReturn("properties");
        when(relation.editing()).thenReturn(new ResolvedDetailRelationEditing(
                ResolvedDetailRelationEditing.Mode.INLINE,
                ResolvedDetailRelationEditing.SaveMode.AGGREGATE_DRAFT, true));
        ManagedDetailRelationGateway gateway = new ManagedDetailRelationGateway(
                catalog, new AllowAllPolicyService(), new ObjectMapper(), List.of());

        var response = gateway.queryRecycleBin(
                FieldUiControlService.MODULE_ALIAS, parentService, "select", "properties");

        assertThat(response.records()).singleElement().satisfies(value ->
                assertThat(((EntityContract) value).getId()).isEqualTo("retained-1"));
        verify(childService).selectDeletedChildRows(any(Criteria.class));
    }

    @Test
    void shouldRunCompiledManagedRelationThroughParentHttpBoundary() throws Exception {
        FieldUiControlService parentService = mock(FieldUiControlService.class);
        FieldUiControlPropertyService childService = mock(FieldUiControlPropertyService.class);
        FieldUiControl parent = parent("select");
        FieldUiControlProperty property = property("property-1", "select");
        property.setTitle("Original title");
        when(parentService.select("select")).thenReturn(parent);
        when(childService.list(any(Criteria.class), any(net.ximatai.muyun.database.core.orm.Sort[].class)))
                .thenReturn(List.of(property));
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
        verify(childService).list(any(Criteria.class),
                any(net.ximatai.muyun.database.core.orm.Sort[].class));
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
                "properties", "property-1", Map.of("attributeAlias", "placeholder", "version", 1)))
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
        AtomicReference<ActionExecutionPolicy> parentPolicy = new AtomicReference<>();
        when(scoped.readScopeByPolicy(eq(FieldUiControlService.MODULE_ALIAS), any(ActionExecutionPolicy.class),
                any(Criteria.class))).thenAnswer(invocation -> {
                    parentPolicy.set(invocation.getArgument(1));
                    return DataScopeCriteriaResult.restricted(invocation.getArgument(2));
                });
        when(scoped.withDataScopeTenant(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(parentService.count(any(Criteria.class))).thenAnswer(ignored ->
                PlatformAction.VIEW.code().equals(parentPolicy.get().actionCode()) ? 1L : 0L);
        when(parentService.select("select")).thenReturn(parent("select"));
        when(childService.pageQuery(any(Criteria.class), any(PageRequest.class),
                any(net.ximatai.muyun.database.core.orm.Sort[].class)))
                .thenReturn(PageResult.of(List.of(), 0, PageRequest.of(1, 20)));
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        when(actionService.findByModuleAliasAndActionCode(FieldUiControlService.MODULE_ALIAS,
                PlatformAction.VIEW.code())).thenReturn(persistedAction(PlatformAction.VIEW.code(), false));
        when(actionService.findByModuleAliasAndActionCode(FieldUiControlService.MODULE_ALIAS,
                PlatformAction.UPDATE.code())).thenReturn(persistedAction(PlatformAction.UPDATE.code(), true));
        ManagedDetailRelationGateway gateway = gateway(parentService, childService,
                mutation(true, true, true), new AllowAllPolicyService(),
                new ActionEndpointContextResolver(actionService));

        gateway.query(FieldUiControlService.MODULE_ALIAS, parentService, "select", "properties", null);
        assertThatThrownBy(() -> gateway.insert(FieldUiControlService.MODULE_ALIAS, parentService, "select",
                "properties", Map.of("attributeAlias", "placeholder")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent is not visible");

        org.mockito.ArgumentCaptor<ActionExecutionPolicy> policies =
                org.mockito.ArgumentCaptor.forClass(ActionExecutionPolicy.class);
        verify(scoped, org.mockito.Mockito.times(2)).readScopeByPolicy(
                eq(FieldUiControlService.MODULE_ALIAS), policies.capture(), any(Criteria.class));
        assertThat(policies.getAllValues()).extracting(ActionExecutionPolicy::actionCode)
                .containsExactly(PlatformAction.VIEW.code(), PlatformAction.UPDATE.code());
        assertThat(policies.getAllValues()).extracting(ActionExecutionPolicy::dataAuth)
                .containsExactly(false, true);
    }

    @Test
    void shouldUseTheSameCompiledSourceActionPolicyForAuthorizationAndChildDataScope() {
        FieldUiControlService parentService = mock(FieldUiControlService.class);
        when(parentService.select("select")).thenReturn(parent("select"));
        FieldUiControlProperty property = property("property-1", "select");
        property.setVersion(1);
        BaseDao<FieldUiControlProperty, String> childDao = mock(BaseDao.class);
        when(childDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(property));
        when(childDao.updateByIdAndVersion(any(FieldUiControlProperty.class), any())).thenReturn(1);
        when(childDao.deleteByIdAndVersion(eq("property-1"), any())).thenReturn(1);
        OverridingPropertyService childService = new OverridingPropertyService(property, childDao);

        ActionExecutionPolicyService authorization = mock(ActionExecutionPolicyService.class);
        ManagedDetailRelationGateway gateway = gateway(parentService, childService,
                mutation(true, true, true), authorization);
        gateway.query(FieldUiControlService.MODULE_ALIAS, parentService, "select", "properties", null);
        gateway.update(FieldUiControlService.MODULE_ALIAS, parentService, "select", "properties", "property-1",
                Map.of("attributeAlias", "placeholder", "version", 1));
        gateway.delete(FieldUiControlService.MODULE_ALIAS, parentService, "select", "properties", "property-1",
                new net.ximatai.muyun.spring.web.RecordActionWebRequest(1));

        assertThat(childService.updateCalls).isEqualTo(1);
        assertThat(childService.deleteCalls).isEqualTo(1);
        assertThat(childService.beforeSoftDeleteCalls).isEqualTo(1);
        assertThat(childService.afterDeleteCalls).isEqualTo(1);
        assertThat(property.getDeleted()).isTrue();
        verify(childDao, org.mockito.Mockito.times(2))
                .updateByIdAndVersion(any(FieldUiControlProperty.class), any());
        verify(childDao, org.mockito.Mockito.never()).deleteByIdAndVersion(any(), any());

        assertThat(childService.scopeCalls).hasSize(3);
        assertThat(childService.scopeCalls).extracting(ScopeCall::moduleAlias)
                .containsOnly(FieldUiControlService.MODULE_ALIAS);
        assertThat(childService.scopeCalls).extracting(ScopeCall::policy)
                .extracting(ActionExecutionPolicy::actionCode)
                .containsExactly("field_ui_control_property_query", "field_ui_control_property_update",
                        "field_ui_control_property_delete");
        assertThat(childService.scopeCalls.getFirst().policy().permissionActionCode())
                .isEqualTo("field_ui_control_property_view");
        assertThat(childService.scopeCalls).extracting(ScopeCall::policy)
                .allMatch(ActionExecutionPolicy::dataAuth);
        assertThat(childService.scopeCalls.getFirst().criteria().getClauses())
                .extracting(net.ximatai.muyun.database.core.orm.CriteriaClause::getField)
                .contains("fieldUiControlAlias");
        assertThat(childService.scopeCalls.get(1).criteria().getClauses())
                .extracting(net.ximatai.muyun.database.core.orm.CriteriaClause::getField)
                .contains("id");

        org.mockito.ArgumentCaptor<ActionExecutionContext> authorized =
                org.mockito.ArgumentCaptor.forClass(ActionExecutionContext.class);
        verify(authorization, org.mockito.Mockito.times(6)).requireAuthorized(authorized.capture());
        assertThat(authorized.getAllValues().stream()
                .filter(context -> context.actionCode().startsWith("field_ui_control_property_"))
                .map(ActionExecutionContext::actionPolicy).toList())
                .containsExactlyElementsOf(childService.scopeCalls.stream().map(ScopeCall::policy).toList());
    }

    @Test
    void shouldPreserveSoftDeleteOptimisticLockThroughManagedGateway() {
        FieldUiControlService parentService = mock(FieldUiControlService.class);
        when(parentService.select("select")).thenReturn(parent("select"));
        FieldUiControlProperty property = property("property-1", "select");
        property.setVersion(3);
        BaseDao<FieldUiControlProperty, String> childDao = mock(BaseDao.class);
        when(childDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(property));
        OverridingPropertyService childService = new OverridingPropertyService(property, childDao);
        ManagedDetailRelationGateway gateway = gateway(parentService, childService,
                mutation(true, true, true), new AllowAllPolicyService());

        assertThatThrownBy(() -> gateway.delete(FieldUiControlService.MODULE_ALIAS, parentService, "select",
                "properties", "property-1", new net.ximatai.muyun.spring.web.RecordActionWebRequest(2)))
                .isInstanceOf(net.ximatai.muyun.spring.ability.OptimisticLockException.class)
                .hasMessageContaining("version conflict");

        assertThat(childService.deleteCalls).isEqualTo(1);
        assertThat(childService.beforeSoftDeleteCalls).isZero();
        assertThat(childService.afterDeleteCalls).isZero();
        verify(childDao, org.mockito.Mockito.never())
                .updateByIdAndVersion(any(FieldUiControlProperty.class), any());
        verify(childDao, org.mockito.Mockito.never()).deleteByIdAndVersion(any(), any());
    }

    @Test
    void shouldReportRelationScopedChangesWithoutImpersonatingParentRootRecords() throws Exception {
        FieldUiControlService parentService = mock(FieldUiControlService.class);
        ManagedDetailRelationGateway gateway = mock(ManagedDetailRelationGateway.class);
        FieldUiControlProperty created = property("property-2", "select");
        when(gateway.insert(eq(FieldUiControlService.MODULE_ALIAS), eq(parentService), eq("select"),
                eq("properties"), any(Map.class))).thenReturn(created);
        when(gateway.update(eq(FieldUiControlService.MODULE_ALIAS), eq(parentService), eq("select"),
                eq("properties"), eq("property-1"), any(Map.class)))
                .thenReturn(property("property-1", "select"));
        when(gateway.delete(eq(FieldUiControlService.MODULE_ALIAS), eq(parentService), eq("select"),
                eq("properties"), eq("property-1"), any())).thenReturn(1);
        ManagedGatewayFieldUiControlWebController controller = new ManagedGatewayFieldUiControlWebController();
        ReflectionTestUtils.setField(controller, "service", parentService);
        ReflectionTestUtils.setField(controller, "managedDetailRelationGateway", gateway);
        ActionExecutionPolicyService allowAll = new AllowAllPolicyService();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ActionEndpointInterceptor(allowAll, new ActionEndpointContextResolver()))
                .addInterceptors(new BusinessMutationInterceptor())
                .setControllerAdvice(new ActionResultResponseAdvice(type -> FieldUiControlService.MODULE_ALIAS,
                        new ObjectMapper()))
                .build();
        String base = "/platform.field_ui_control/view/select/relations/properties";

        mvc.perform(post(base + "/insert").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributeAlias\":\"placeholder\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.changes[0].type").value("record-created"))
                .andExpect(jsonPath("$.changes[0].moduleAlias").value(FieldUiControlService.MODULE_ALIAS))
                .andExpect(jsonPath("$.changes[0].resourceKey").value("properties"))
                .andExpect(jsonPath("$.changes[0].scope").value("select"))
                .andExpect(jsonPath("$.changes[0].recordId").value("property-2"));
        mvc.perform(post(base + "/update/property-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributeAlias\":\"placeholder\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].type").value("record-updated"))
                .andExpect(jsonPath("$.changes[0].resourceKey").value("properties"))
                .andExpect(jsonPath("$.changes[0].scope").value("select"))
                .andExpect(jsonPath("$.changes[0].recordId").value("property-1"));
        mvc.perform(post(base + "/delete/property-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].type").value("record-deleted"))
                .andExpect(jsonPath("$.changes[0].resourceKey").value("properties"))
                .andExpect(jsonPath("$.changes[0].scope").value("select"))
                .andExpect(jsonPath("$.changes[0].recordId").value("property-1"));
    }

    private static MockMvc mvc(FieldUiControlService parentService, ManagedDetailRelationGateway gateway,
                               ActionExecutionPolicyService policy) {
        ManagedGatewayFieldUiControlWebController controller = new ManagedGatewayFieldUiControlWebController();
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
        return gateway(parentService, childService, mutation, policy, new ActionEndpointContextResolver());
    }

    private static ManagedDetailRelationGateway gateway(FieldUiControlService parentService,
                                                        FieldUiControlPropertyService childService,
                                                        ResolvedDetailRelationMutationContract mutation,
                                                        ActionExecutionPolicyService policy,
                                                        ActionEndpointContextResolver actionContextResolver) {
        ModuleExecutionPlanCatalog catalog = mock(ModuleExecutionPlanCatalog.class);
        ModuleExecutionPlan plan = mock(ModuleExecutionPlan.class);
        ResolvedModuleUiDescriptor descriptor = mock(ResolvedModuleUiDescriptor.class);
        when(catalog.find(FieldUiControlService.MODULE_ALIAS)).thenReturn(Optional.of(plan));
        when(plan.uiDescriptor()).thenReturn(descriptor);
        when(plan.actions()).thenReturn(relationActions());
        when(descriptor.detailRelations()).thenReturn(List.of(new ResolvedDetailRelationDescriptor(
                "properties", "控件属性", false, FieldUiControlService.MODULE_ALIAS, "field_ui_control",
                FieldUiControlPropertyService.MODULE_ALIAS, "field_ui_control_property", "fieldUiControlAlias",
                new ResolvedDetailRelationQueryContract(null, null, null, false, false, null,
                        net.ximatai.muyun.spring.ability.query.QuerySchema.from(
                                net.ximatai.muyun.spring.ability.query.QueryDescriptor.builder("field_ui_control_property").build()),
                        true, "field_ui_control_property_query", null, List.of()),
                mutation, null,
                new ResolvedDetailRelationEditing(ResolvedDetailRelationEditing.Mode.INLINE,
                        ResolvedDetailRelationEditing.SaveMode.AGGREGATE_DRAFT), true)));
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
        return new ManagedDetailRelationGateway(catalog, policy, actionContextResolver,
                new ObjectMapper(), List.of(handler));
    }

    private static ResolvedDetailRelationMutationContract mutation(boolean create, boolean update, boolean delete) {
        return new ResolvedDetailRelationMutationContract(create, update, delete,
                create ? "field_ui_control_property_create" : null,
                update ? "field_ui_control_property_update" : null,
                delete ? "field_ui_control_property_delete" : null);
    }

    private static List<StaticModuleActionDefinition> relationActions() {
        return List.of(
                StaticModuleActionDefinition.platformAction(PlatformAction.VIEW),
                StaticModuleActionDefinition.platformAction(PlatformAction.UPDATE),
                relationAction("field_ui_control_property_query", "field_ui_control_property_view", true),
                relationAction("field_ui_control_property_create", "field_ui_control_property_create", false),
                relationAction("field_ui_control_property_update", "field_ui_control_property_update", true),
                relationAction("field_ui_control_property_delete", "field_ui_control_property_delete", true)
        );
    }

    private static StaticModuleActionDefinition relationAction(String actionCode, String permissionActionCode,
                                                               boolean dataAuth) {
        return new StaticModuleActionDefinition(actionCode, permissionActionCode, actionCode,
                dataAuth ? EntityActionLevel.RECORD : EntityActionLevel.LIST,
                EntityActionAccessMode.AUTH_REQUIRED, true, dataAuth, ActionDefaultGrantPolicy.NONE);
    }

    private static PlatformModuleAction persistedAction(String actionCode, boolean dataAuth) {
        PlatformModuleAction action = new PlatformModuleAction();
        action.setModuleAlias(FieldUiControlService.MODULE_ALIAS);
        action.setActionCode(actionCode);
        action.setPermissionActionCode(actionCode);
        action.setActionLevel(EntityActionLevel.RECORD);
        action.setAccessMode(EntityActionAccessMode.AUTH_REQUIRED);
        action.setActionAuth(Boolean.TRUE);
        action.setDataAuth(dataAuth);
        action.setDefaultGrantPolicy(ActionDefaultGrantPolicy.NONE);
        action.setEnabled(Boolean.TRUE);
        return action;
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

    private record ScopeCall(String moduleAlias, ActionExecutionPolicy policy, Criteria criteria) { }

    private static class ScopedPropertyService extends FieldUiControlPropertyService
            implements DataScopeAbility<FieldUiControlProperty> {
        private final FieldUiControlProperty record;
        protected final List<ScopeCall> scopeCalls = new ArrayList<>();

        @SuppressWarnings("unchecked")
        protected ScopedPropertyService(FieldUiControlProperty record,
                                        BaseDao<FieldUiControlProperty, String> dao) {
            super(dao, mock(FieldUiControlService.class), mock(FieldSpecService.class));
            this.record = record;
        }

        @Override
        public DataScopeCriteriaService getDataScopeCriteriaService() {
            return new DataScopeCriteriaService() {
                @Override
                public DataScopeCriteriaResult resolveReadScope(String moduleAlias, String actionCode,
                                                                Criteria criteria, Optional<CurrentUser> currentUser) {
                    throw new AssertionError("policy overload is required");
                }

                @Override
                public DataScopeCriteriaResult resolveReadScope(String moduleAlias, ActionExecutionPolicy policy,
                                                                Criteria criteria, Optional<CurrentUser> currentUser) {
                    scopeCalls.add(new ScopeCall(moduleAlias, policy, criteria));
                    return DataScopeCriteriaResult.restricted(criteria);
                }

                @Override
                public DataScopeCriteriaResult resolveReadScope(String moduleAlias, ActionExecutionPolicy policy,
                                                                Criteria criteria, Optional<CurrentUser> currentUser,
                                                                DataScopeFieldMapping fieldMapping) {
                    return resolveReadScope(moduleAlias, policy, criteria, currentUser);
                }
            };
        }

        @Override
        public PageResult<FieldUiControlProperty> pageQuery(Criteria criteria, PageRequest pageRequest,
                                                            net.ximatai.muyun.database.core.orm.Sort... sorts) {
            return PageResult.of(List.of(record), 1, pageRequest);
        }

        @Override
        public long count(Criteria criteria) {
            return 1;
        }

        @Override
        public FieldUiControlProperty select(String id) {
            return record.getId().equals(id) ? record : null;
        }

    }

    /** Test-only host for the independent association gateway contract. */
    private static final class ManagedGatewayFieldUiControlWebController extends FieldUiControlWebController
            implements ManagedDetailRelationWeb<FieldUiControl, FieldUiControlService> {
    }

    private static final class OverridingPropertyService extends ScopedPropertyService {
        private int updateCalls;
        private int deleteCalls;
        private int beforeSoftDeleteCalls;
        private int afterDeleteCalls;

        private OverridingPropertyService(FieldUiControlProperty record,
                                          BaseDao<FieldUiControlProperty, String> dao) {
            super(record, dao);
        }

        @Override
        public int update(FieldUiControlProperty entity) {
            updateCalls++;
            return super.update(entity);
        }

        @Override
        public int delete(String id, Integer version) {
            deleteCalls++;
            return super.delete(id, version);
        }

        @Override
        public void beforeSoftDelete(FieldUiControlProperty entity) {
            beforeSoftDeleteCalls++;
        }

        @Override
        public void afterDelete(String id, FieldUiControlProperty entity, int deleted) {
            afterDeleteCalls++;
        }
    }
}
