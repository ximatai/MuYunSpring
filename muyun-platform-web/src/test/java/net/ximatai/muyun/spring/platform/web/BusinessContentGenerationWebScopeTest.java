package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.child.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.platform.content.*;
import net.ximatai.muyun.spring.platform.menu.*;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import org.junit.jupiter.api.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessContentGenerationWebScopeTest {
    private static final String MODULE = "test.generation";
    private static final BusinessContentService.Location LOCATION = BusinessContentService.Location.record("record");
    private final BusinessContentService content = mock(BusinessContentService.class);
    private final ModuleExecutionPlanCatalog plans = mock(ModuleExecutionPlanCatalog.class);
    private final MenuService menus = mock(MenuService.class);
    private final DynamicRecordService dynamics = mock(DynamicRecordService.class);
    private final PlatformModuleActionService actions = mock(PlatformModuleActionService.class);
    private final ModuleTenantScope tenantScopes = mock(ModuleTenantScope.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private GenericApplicationContext context;
    private Controller controller;
    private MockHttpServletRequest request;
    private Row row;
    private BusinessContentGenerationWebScope scope;

    @BeforeEach @SuppressWarnings("unchecked") void install() throws Exception {
        context = new GenericApplicationContext(); controller = new Controller(); row = new Row();
        row.setId("record"); row.setTenantId("tenant-a"); row.setProjectId("project-one"); row.setDescription("private raw text");
        when(controller.service.getModuleAlias()).thenReturn(MODULE); doReturn(Row.class).when(controller.service).modelClass();
        when(content.requireOwner(any(), any())).thenReturn(row);
        context.getBeanFactory().registerSingleton("recordController", controller);
        context.getBeanFactory().registerSingleton("catalog", new StaticAbilityCatalog(List.of(controller.service)));
        context.getBeanFactory().registerSingleton("dynamicRecords", dynamics);
        context.getBeanFactory().registerSingleton("menus", menus); context.refresh();
        context.getBeanFactory().registerSingleton("tenantScopes", tenantScopes);
        scope = new BusinessContentGenerationWebScope(context, plans, content, context.getBeanProvider(StaticAbilityCatalog.class),
                context.getBeanProvider(DynamicRecordService.class), context.getBeanProvider(MenuService.class), json,
                new ActionEndpointContextResolver(actions), context.getBeanProvider(ModuleTenantScope.class));
        plan("one", List.of());
        request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(controller, Controller.class.getMethod("view", String.class)));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "tenant-a"));
        TenantContext.setTenantId("tenant-a");
    }
    @AfterEach void close() {
        RequestContextHolder.resetRequestAttributes(); CurrentUserContext.clear(); TenantContext.clear();
        ActionExecutionContextHolder.clear(); context.close();
    }

    @Test void workerReopensTheRealEntryWithoutHttpAndRestoresItsCallersActionContext() {
        var reference = scope.capture(controller, LOCATION, "description");
        assertThat(reference.payload()).doesNotContain("private raw text", "Reader");
        var previousRequest = RequestContextHolder.getRequestAttributes();
        var outer = ActionExecutionContext.ofPlatformAction("other.module", PlatformAction.UPDATE, Set.of(), CurrentUserContext.currentUser());
        try (var ignored = ActionExecutionContextHolder.use(outer)) {
            assertThat(scope.<String>withAccess(reference, MODULE, LOCATION, "description", ability -> {
                assertThat(ability).isSameAs(controller.service);
                assertThat(RequestContextHolder.getRequestAttributes()).isNull();
                assertThat(ActionExecutionContextHolder.current().orElseThrow().actionCode()).isEqualTo("view");
                return "allowed";
            })).isEqualTo("allowed");
            assertThat(ActionExecutionContextHolder.current()).contains(outer);
        }
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(previousRequest);
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, BusinessContentService.Location.record("different"),
                "description", ability -> "not allowed")).isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test void requiredNavigatorScopeIsVerifiedAtCaptureAndCannotDisappearAfterTheRequest() {
        var binding = PageContextBindingDefinition.navigatorList("project", "projectId", NavigatorListQueryMode.REQUIRED_SCOPE);
        plan("one", List.of(binding));
        assertThatThrownBy(() -> scope.capture(controller, LOCATION, "description")).hasMessageContaining("scope is required");
        var selectedRequest = new MockHttpServletRequest();
        selectedRequest.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE));
        request = selectedRequest; RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        request.addHeader(PageContextScopePolicy.CONTEXT_HEADER, "{\"project\":\"project-one\"}");
        var reference = scope.capture(controller, LOCATION, "description");
        assertThat(reference.payload()).doesNotContain("project-one");
        RequestContextHolder.resetRequestAttributes();
        row.setVersion(7); // An unrelated business revision does not invalidate an unchanged text/workspace.
        assertThat(scope.<String>withAccess(reference, MODULE, LOCATION, "description", ability -> "allowed")).isEqualTo("allowed");
        row.setProjectId("project-two");
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        row.setProjectId("project-one"); plan("two", List.of(binding));
        assertThat(scope.<String>withAccess(reference, MODULE, LOCATION, "description", ability -> "allowed"))
                .isEqualTo("allowed"); // A rebuilt cache is not a changed business read contract.
        plan("three", List.of());
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test void opaqueSelectionIsResolvedAgainForTheCurrentReaderIncludingPresentNull() {
        var binding = PageContextBindingDefinition.resolvedSelection("projectScope", PageContextTarget.LIST_QUERY, "projectId");
        plan("one", List.of(binding)); row.setProjectId(null);
        request.addHeader(PageSelectionContextRequestHeader.HEADER_NAME, "{\"kind\":\"projectScope\",\"key\":\"platform\"}");
        var allowed = new AtomicBoolean(true); var calls = new AtomicInteger();
        var selectedOwner = new AtomicReference<String>();
        controller.resolvers = new PageSelectionContextResolverRegistry(List.of(new PageSelectionContextResolver() {
            public String selectionKind() { return "projectScope"; }
            public ResolvedPageSelectionContext resolve(PageSelectionContextRequest selection) {
                calls.incrementAndGet();
                assertThat(selection.currentUser().userId()).isEqualTo("reader");
                assertThat(selection.action()).isEqualTo(PlatformAction.VIEW);
                if (!allowed.get()) throw new PlatformAccessDeniedException("selection withdrawn");
                return new ResolvedPageSelectionContext("projectScope", "platform", Map.of("projectId", PageContextValue.of(selectedOwner.get())));
            }
        }));
        var reference = scope.capture(controller, LOCATION, "description");
        RequestContextHolder.resetRequestAttributes();
        assertThat(scope.<String>withAccess(reference, MODULE, LOCATION, "description", ability -> "allowed")).isEqualTo("allowed");
        assertThat(calls).hasValue(2);
        selectedOwner.set("different-project"); row.setProjectId("different-project");
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        selectedOwner.set(null); row.setProjectId(null);
        allowed.set(false);
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test void menuIdentityParametersVisibilityAndOwnedBusinessPolicyAreAllLiveConstraints() {
        var menu = new Menu(); menu.setId("menu"); menu.setModuleAlias(MODULE);
        menu.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z")); menu.setEntryParamsJson("{\"scope\":\"private-entry-value\"}");
        when(menus.currentUserVisibleMenu("menu")).thenReturn(menu); MenuEntryRequestContext.bind(request, menu);
        var deny = new AtomicBoolean();
        controller.policy = new ModulePageEntryPolicy<>() {
            public boolean supports(MenuEntryRequestContext entry) { return "menu".equals(entry.menuId()); }
            public void requireRecord(MenuEntryRequestContext entry, PlatformAction action, Row record) {
                if (deny.get()) throw new PlatformAccessDeniedException("entry withdrawn");
            }
        };
        var reference = scope.capture(controller, LOCATION, "description");
        assertThat(reference.payload()).doesNotContain("private-entry-value");
        RequestContextHolder.resetRequestAttributes();
        assertThat(scope.<String>withAccess(reference, MODULE, LOCATION, "description", ability -> "allowed")).isEqualTo("allowed");
        deny.set(true);
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        deny.set(false); menu.setEntryParamsJson("{}");
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        menu.setEntryParamsJson("{\"scope\":\"private-entry-value\"}"); controller.policy = null;
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        when(menus.currentUserVisibleMenu("menu")).thenThrow(new PlatformAccessDeniedException("menu hidden"));
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test void hiddenFieldsAndAChangedOwningViewAreRejectedBeforeCapturingWork() {
        assertThatThrownBy(() -> scope.capture(controller, LOCATION, "unexposed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        clearInvocations(content); controller.denied = true;
        assertThatThrownBy(() -> scope.capture(controller, LOCATION, "description"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        verifyNoInteractions(content);
    }

    @Test void dynamicRootAbilityIsResolvedWithoutCallingRequestOnlyControllerServiceInTheWorker() {
        context.getDefaultListableBeanFactory().destroySingleton("catalog");
        var dynamic = mock(DynamicEntityOperations.class); when(dynamic.getModuleAlias()).thenReturn(MODULE);
        when(dynamics.mainEntity(MODULE)).thenReturn(dynamic);
        var current = plans.find(MODULE).orElseThrow();
        var dynamicPlan = new ModuleExecutionPlan(MODULE, current.versionKey(), current.uiDescriptor(), current.readModel(), current.pageContextBindings());
        when(plans.find(MODULE)).thenReturn(Optional.of(dynamicPlan));
        var reference = scope.capture(controller, LOCATION, "description");
        RequestContextHolder.resetRequestAttributes(); controller.serviceNeedsRequest = true;
        assertThat(scope.<CrudAbility<?>>withAccess(reference, MODULE, LOCATION, "description", ability -> ability)).isSameAs(dynamic);
        verify(content).requireOwner(dynamic, LOCATION);
    }

    @Test void livePublishedActionGovernanceIsResolvedAgainInsteadOfReusingCompiledDefaults() {
        var reference = scope.capture(controller, LOCATION, "description"); RequestContextHolder.resetRequestAttributes();
        var configured = new PlatformModuleAction(); configured.setModuleAlias(MODULE); configured.setActionCode("view");
        configured.setActionLevel(EntityActionLevel.RECORD); configured.setDataAuth(true); configured.setActionAuth(true);
        configured.setPermissionActionCode("restrictedView"); configured.setEnabled(true);
        when(actions.findByModuleAliasAndActionCode(MODULE, "view")).thenReturn(configured);
        scope.withAccess(reference, MODULE, LOCATION, "description", ability -> {
            var policy = ActionExecutionContextHolder.current().orElseThrow().actionPolicy();
            assertThat(policy.requiresDataScope()).isTrue(); assertThat(policy.permissionActionCode()).isEqualTo("restrictedView");
            return null;
        });
        configured.setEnabled(false);
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformException.class).hasMessageContaining("not published");
    }

    @Test void theOriginalBusinessExecutionTenantMustStillPassTheNormalDeliveryGate() {
        var reference = scope.capture(controller, LOCATION, "description"); RequestContextHolder.resetRequestAttributes();
        clearInvocations(content);
        doThrow(new PlatformAccessDeniedException("execution tenant withdrawn")).when(tenantScopes).requireActiveTenantIfRequired(MODULE);
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class).hasMessage("execution tenant withdrawn");
        verifyNoInteractions(content);
    }

    @Test void submittedRootUsesTheActualSavingServiceAndRetainsTheNormalReadingScope() {
        when(controller.service.mutationServiceIdentity()).thenCallRealMethod();
        var change = new BusinessContentOriginalChanged(controller.service, row, "description", "original-one");
        var submission = scope.captureSubmission(change).orElseThrow();
        assertThat(submission.location()).isEqualTo(LOCATION);
        assertThat(submission.originalRevisionId()).isEqualTo("original-one");
        assertThat(scope.<CrudAbility<?>>withAccess(submission.scope(), MODULE, LOCATION, "description", ability -> ability))
                .isSameAs(controller.service);
        CrudAbility<?> other = mock(CrudAbility.class);
        when(other.getModuleAlias()).thenReturn("other.module");
        assertThat(scope.captureSubmission(new BusinessContentOriginalChanged(other, row, "description", "original-one"))).isEmpty();
        RequestContextHolder.resetRequestAttributes();
        assertThat(scope.captureSubmission(change)).isEmpty();
    }

    @Test void durableReadContractSurvivesIndependentCacheRebuildAndFieldOrderingButRejectsChangedExecutionFacts() {
        var original = plans.find(MODULE).orElseThrow();
        var description = new ResolvedModuleReadField("main", null, "description", false);
        var project = new ResolvedModuleReadField("main", null, "projectId", false);
        var first = new ModuleExecutionPlan(MODULE, "static-first-jvm-cache", original.uiDescriptor(),
                new ResolvedModuleReadModel(MODULE, "main", List.of(description, project)), List.of(), null, null,
                List.of(), original.actions(), true);
        when(plans.find(MODULE)).thenReturn(Optional.of(first));
        var reference = scope.capture(controller, LOCATION, "description");
        var rebuilt = new ModuleExecutionPlan(MODULE, "static-second-jvm-cache", original.uiDescriptor(),
                new ResolvedModuleReadModel(MODULE, "main", List.of(project, description)), List.of(), null, null,
                List.of(), original.actions(), true);
        when(plans.find(MODULE)).thenReturn(Optional.of(rebuilt));
        assertThat(scope.<String>withAccess(reference, MODULE, LOCATION, "description", ability -> "allowed")).isEqualTo("allowed");
        var changed = new ModuleExecutionPlan(MODULE, rebuilt.versionKey(), rebuilt.uiDescriptor(), rebuilt.readModel(),
                List.of(), null, null, List.of(), rebuilt.actions(), false);
        when(plans.find(MODULE)).thenReturn(Optional.of(changed));
        assertThatThrownBy(() -> scope.withAccess(reference, MODULE, LOCATION, "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test @SuppressWarnings("unchecked") void submittedStaticChildUsesItsDeclaredOwningKeyAndRejectsAmbiguousRelations() {
        var aggregate = mock(Aggregate.class); controller.service = aggregate;
        when(aggregate.getModuleAlias()).thenReturn(MODULE); when(aggregate.mutationServiceIdentity()).thenCallRealMethod();
        ChildAbility<Row> child = mock(ChildAbility.class);
        when(child.getModuleAlias()).thenReturn(MODULE); when(child.mutationServiceIdentity()).thenCallRealMethod();
        var relation = new ChildRelation<Row, Row>("details", child, (record, id) -> record.setProjectId(id),
                "projectId", ignored -> List.of(), Row::getProjectId);
        when(aggregate.childRelations()).thenReturn(List.of(relation)); childPlan();
        var saved = new Row(); saved.setId("child"); saved.setProjectId("record");
        var change = new BusinessContentOriginalChanged(child, saved, "description", "child-original");
        assertThat(scope.captureSubmission(change).orElseThrow().location())
                .isEqualTo(new BusinessContentService.Location("record", "details", "child"));
        var captured = scope.captureSubmission(change).orElseThrow();
        var relationView = plans.find(MODULE).orElseThrow().uiDescriptor().detailRelations().getFirst();
        when(relationView.parentBinding()).thenReturn("changedOwnerKey");
        assertThatThrownBy(() -> scope.withAccess(captured.scope(), MODULE, captured.location(), "description", ability -> "not allowed"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        when(aggregate.childRelations()).thenReturn(List.of(relation, relation));
        assertThat(scope.captureSubmission(change)).isEmpty();
    }

    @Test @SuppressWarnings("unchecked") void submittedDynamicChildUsesPublishedEntityOwnershipAndFieldExposure() {
        var rootDao = mock(DynamicRecordDao.class); var childDao = mock(DynamicRecordDao.class);
        var root = mock(EntityDefinition.class); when(root.alias()).thenReturn("main"); when(rootDao.getEntity()).thenReturn(root);
        var detail = mock(EntityDefinition.class); when(detail.alias()).thenReturn("inspection"); when(childDao.getEntity()).thenReturn(detail);
        doReturn(rootDao).when(controller.service).getDao();
        CrudAbility<DynamicRecord> child = mock(CrudAbility.class); when(child.getModuleAlias()).thenReturn(MODULE);
        doReturn(childDao).when(child).getDao();
        var module = mock(ModuleDefinition.class); when(module.moduleAlias()).thenReturn(MODULE);
        when(module.relations()).thenReturn(List.of(EntityRelationDefinition.child("details", "main", "inspection", "ownerId")));
        when(dynamics.moduleDefinitions()).thenReturn(List.of(module)); childPlan();
        var saved = mock(DynamicRecord.class); when(saved.getId()).thenReturn("child"); when(saved.getValue("ownerId")).thenReturn("record");
        var change = new BusinessContentOriginalChanged(child, saved, "description", "child-original");
        assertThat(scope.captureSubmission(change).orElseThrow().location())
                .isEqualTo(new BusinessContentService.Location("record", "details", "child"));
        assertThatThrownBy(() -> scope.captureSubmission(new BusinessContentOriginalChanged(child, saved, "hidden", "child-original")))
                .isInstanceOf(PlatformAccessDeniedException.class);
        when(module.relations()).thenReturn(List.of(EntityRelationDefinition.child("details", "unrelated", "inspection", "ownerId")));
        assertThat(scope.captureSubmission(change)).isEmpty();
    }

    private void childPlan() {
        var plan = plans.find(MODULE).orElseThrow();
        var detail = mock(ResolvedDetailRelationDescriptor.class);
        when(detail.code()).thenReturn("details"); when(detail.targetModuleAlias()).thenReturn(MODULE);
        when(detail.targetEntityAlias()).thenReturn("inspection");
        when(plan.uiDescriptor().detailRelations()).thenReturn(List.of(detail));
        var updated = new ModuleExecutionPlan(MODULE, plan.versionKey(), plan.uiDescriptor(),
                new ResolvedModuleReadModel(MODULE, "main", List.of(
                        new ResolvedModuleReadField("main", null, "description", false),
                        new ResolvedModuleReadField("inspection", "details", "description", false))), List.of());
        when(plans.find(MODULE)).thenReturn(Optional.of(updated));
    }

    interface Aggregate extends CrudAbility<Row>, ChildrenAbility<Row> { }

    private void plan(String version, List<PageContextBindingDefinition> bindings) {
        controller.bindings = bindings;
        var ui = mock(ResolvedModuleUiDescriptor.class); when(ui.moduleAlias()).thenReturn(MODULE); when(ui.detailRelations()).thenReturn(List.of());
        var plan = new ModuleExecutionPlan(MODULE, version, ui,
                new ResolvedModuleReadModel(MODULE, "main", List.of(new ResolvedModuleReadField("main", null, "description", false))),
                bindings, null, null, List.of(), List.of(StaticModuleActionDefinition.platformAction(PlatformAction.VIEW)), true);
        when(plans.find(MODULE)).thenReturn(Optional.of(plan));
    }
    static class Controller implements CrudWeb<Row, CrudAbility<Row>> {
        @SuppressWarnings("unchecked") CrudAbility<Row> service = mock(CrudAbility.class);
        List<PageContextBindingDefinition> bindings = List.of(); boolean denied; boolean serviceNeedsRequest;
        ModulePageEntryPolicy<Row> policy;
        PageSelectionContextResolverRegistry resolvers = new PageSelectionContextResolverRegistry(List.of());
        public CrudAbility<Row> service() {
            if (serviceNeedsRequest && RequestContextHolder.getRequestAttributes() == null) throw new AssertionError("request-only service called");
            return service;
        }
        public String webScopeName() { return MODULE; }
        public List<PageContextBindingDefinition> recordScopeBindings() { return bindings; }
        public PageSelectionContextResolverRegistry pageSelectionContextResolvers() { return resolvers; }
        public ModulePageEntryPolicy<Row> modulePageEntryPolicy() { return policy; }
        public Row view(String id) { if (denied) throw new PlatformAccessDeniedException("owning view withdrawn"); return new Row(); }
    }
    public static class Row extends StandardEntity {
        private String projectId;
        private String description;
        public String getProjectId() { return projectId; }
        public void setProjectId(String value) { projectId = value; }
        public String getDescription() { return description; }
        public void setDescription(String value) { description = value; }
    }
}
