package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.platform.web.ActionEndpointContextResolver;
import net.ximatai.muyun.spring.platform.web.ActionEndpointInterceptor;
import net.ximatai.muyun.spring.web.ActionResultResponseAdvice;
import net.ximatai.muyun.spring.web.BusinessMutationInterceptor;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.web.MuYunSpringJacksonConfiguration;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.platform.web.endpoint.StaticAbilityWebEndpointRegistrar;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRefreshResult;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.MetadataView;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewField;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewService;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldProtectionConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldProtectionConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffect;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffectService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilter;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilterService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRule;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRuleService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItem;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.dynamic.metadata.StaticEntityDefinitionCompiler;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigPublishService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplate;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformConfigurationWebControllerTest {

    private static MockMvc abilityAwareMvc(Object... controllers) {
        ObjectMapper objectMapper = applicationObjectMapper();
        return MockMvcBuilders.standaloneSetup(controllers)
                .setCustomHandlerMapping(() -> new AbilityAwareHandlerMapping(objectMapper))
                .setControllerAdvice(new StandardModuleWireResponseAdvice(objectMapper))
                .build();
    }

    private static MockMvc abilityAwareMvcWithManagedJackson(Object... controllers) {
        ObjectMapper objectMapper = applicationObjectMapper();
        return MockMvcBuilders.standaloneSetup(controllers)
                .setCustomHandlerMapping(() -> new AbilityAwareHandlerMapping(objectMapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new StandardModuleWireResponseAdvice(objectMapper))
                .build();
    }

    private static ObjectMapper applicationObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.registerModule(new MuYunSpringJacksonConfiguration().codeTitleEnumJacksonModule());
        return objectMapper;
    }

    private static final class AbilityAwareHandlerMapping extends RequestMappingHandlerMapping {
        private final ObjectMapper objectMapper;
        private final RegisteredWebEndpointCatalog endpoints;

        private AbilityAwareHandlerMapping(ObjectMapper objectMapper) {
            this(objectMapper, new RegisteredWebEndpointCatalog());
        }

        private AbilityAwareHandlerMapping(ObjectMapper objectMapper, RegisteredWebEndpointCatalog endpoints) {
            this.objectMapper = objectMapper;
            this.endpoints = endpoints;
        }

        @Override
        public void afterPropertiesSet() {
            super.afterPropertiesSet();
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            ObjectProvider<net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade> provider =
                    beanFactory.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class);
            new StaticAbilityWebEndpointRegistrar(
                    obtainApplicationContext(),
                    this,
                    endpoints,
                    provider,
                    objectMapper
            ).afterSingletonsInstantiated();
        }
    }

    @Test
    void shouldExposeStandardModuleTreeWithinNavigatorApplicationScope() throws Exception {
        PlatformModuleService service = mock(PlatformModuleService.class);
        PlatformModuleWebController controller = new PlatformModuleWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        PlatformModule root = module("platform.sales", "platform", null);
        PlatformModule child = module("platform.sales.order", "platform", "platform.sales");
        when(service.children(any(Criteria.class), any(String.class))).thenAnswer(invocation -> {
            String parentId = invocation.getArgument(1);
            if (net.ximatai.muyun.spring.ability.TreeAbility.ROOT_ID.equals(parentId)) return List.of(root);
            if ("platform.sales".equals(parentId)) return List.of(child);
            return List.of();
        });
        when(service.selectInScope(any(Criteria.class), eq("platform.sales"))).thenReturn(root);

        MockMvc mvc = abilityAwareMvc(controller);
        mvc.perform(post("/platform.module/tree/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalQueryValues\":{\"applicationAlias\":\"platform\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("platform.sales"))
                .andExpect(jsonPath("$.records[0].children[0].record.id").value("platform.sales.order"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service, org.mockito.Mockito.atLeastOnce())
                .children(criteria.capture(), any(String.class));
        assertClause(criteria.getAllValues().getFirst(), "applicationAlias", "platform");
    }

    @Test
    void shouldRunModuleCrudSchemasFromCompiledPlanWithoutReenteringDsl() throws Exception {
        PlanOnlyPlatformModuleWebController controller = new PlanOnlyPlatformModuleWebController();
        ReflectionTestUtils.setField(controller, "service", new PlatformModuleService(mock(net.ximatai.muyun.spring.ability.BaseDao.class)));
        ReflectionTestUtils.setField(controller, "standardModuleWebRuntime", platformModuleRuntime(controller));
        controller.rejectDefinitionLookup();
        MockMvc mvc = abilityAwareMvc(controller);

        mvc.perform(get("/platform.module/query/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeName").value("platform.module"));
        mvc.perform(get("/platform.module/form/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].name").value("alias"));
        mvc.perform(post("/platform.module/tree/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalQueryValues\":{\"applicationAlias\":\"platform\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldPreservePlatformModuleCodeValuesWhenStandardWireAdaptsCrudResponses() throws Exception {
        PlatformModuleService service = mock(PlatformModuleService.class);
        PlatformModuleWebController controller = new PlatformModuleWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        controller.setStandardModuleWebRuntime(platformModuleRuntime(controller));

        PlatformModule saved = module("platform.sales", "platform", null);
        saved.setTitle("销售");
        saved.setModuleKind(net.ximatai.muyun.spring.platform.module.ModuleKind.STATIC);
        saved.setEntryType(net.ximatai.muyun.spring.platform.module.ModuleEntryType.MODULE);
        when(service.select(any(String.class))).thenReturn(saved);
        when(service.insert(any(PlatformModule.class))).thenReturn("platform.sales");
        when(service.update(any(PlatformModule.class))).thenReturn(1);

        MockMvc mvc = abilityAwareMvcWithManagedJackson(controller);
        mvc.perform(get("/platform.module/view/platform.sales")
                        .header("X-MuYun-Page-Context", "{\"application\":\"platform\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleKind").value("static"))
                .andExpect(jsonPath("$.entryType").value("module"));
        mvc.perform(post("/platform.module/insert").header("X-MuYun-Page-Context", "{\"application\":\"platform\"}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"platform.sales\",\"title\":\"销售\",\"applicationAlias\":\"platform\",\"moduleKind\":\"dynamic\",\"entryType\":\"route\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.moduleKind").value("static"))
                .andExpect(jsonPath("$.entryType").value("module"));
        mvc.perform(post("/platform.module/update/platform.sales").header("X-MuYun-Page-Context", "{\"application\":\"platform\"}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"销售\",\"applicationAlias\":\"platform\",\"moduleKind\":\"dynamic\",\"entryType\":\"link\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleKind").value("static"))
                .andExpect(jsonPath("$.entryType").value("module"));
    }

    @Test
    void shouldExposeStandardModuleSubtreeByModuleAlias() throws Exception {
        PlatformModuleService service = mock(PlatformModuleService.class);
        PlatformModuleWebController controller = new PlatformModuleWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        PlatformModule root = module("platform.sales", "platform", null);
        PlatformModule child = module("platform.sales.order", "platform", "platform.sales");
        when(service.selectInScope(any(Criteria.class), eq("platform.sales"))).thenReturn(root);
        when(service.children(any(Criteria.class), eq("platform.sales"))).thenReturn(List.of(child));
        when(service.children(any(Criteria.class), eq("platform.sales.order"))).thenReturn(List.of());

        MockMvc mvc = abilityAwareMvc(controller);
        mvc.perform(get("/platform.module/tree/platform.sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("platform.sales"))
                .andExpect(jsonPath("$.records[0].children[0].record.id").value("platform.sales.order"));

        verify(service, org.mockito.Mockito.atLeastOnce()).selectInScope(any(Criteria.class), eq("platform.sales"));
    }

    @Test
    void shouldExposeUnscopedModuleForestForReadOnlyCatalogConsumers() throws Exception {
        PlatformModuleService service = mock(PlatformModuleService.class);
        PlatformModuleWebController controller = new PlatformModuleWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        PlatformModule platform = module("platform.sales", "platform", null);
        PlatformModule iam = module("iam.role", "iam", null);
        when(service.children(any(Criteria.class), any(String.class))).thenAnswer(invocation ->
                net.ximatai.muyun.spring.ability.TreeAbility.ROOT_ID.equals(invocation.getArgument(1))
                        ? List.of(platform, iam) : List.of());

        MockMvc mvc = abilityAwareMvc(controller);
        mvc.perform(get("/platform.module/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("platform.sales"))
                .andExpect(jsonPath("$.records[1].record.id").value("iam.role"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service, org.mockito.Mockito.atLeastOnce())
                .children(criteria.capture(), any(String.class));
        assertThat(clauses(criteria.getAllValues().getFirst()))
                .extracting(CriteriaClause::getField)
                .doesNotContain("applicationAlias");
    }

    @Test
    void shouldCaptureRequestTenantBeforeDelegatingOpenApiCatalogInSystemScope() {
        PlatformOpenApiCatalogService catalogService = mock(PlatformOpenApiCatalogService.class);
        OpenApiModuleCatalogItem item = new OpenApiModuleCatalogItem(
                "crm.customer", "客户", "dynamic", "/crm.customer/openapi");
        when(catalogService.discover("tenant-a")).thenAnswer(invocation -> {
            assertThat(TenantContext.isSystem()).isTrue();
            return List.of(item);
        });
        PlatformModuleWebController controller = new PlatformModuleWebController();
        controller.configureOpenApiCatalog(catalogService);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(controller.openApiCatalog()).containsExactly(item);
        }

        verify(catalogService).discover("tenant-a");
    }

    @Test
    void shouldDelegateOpenApiCatalogWithoutInventingATenant() {
        PlatformOpenApiCatalogService catalogService = mock(PlatformOpenApiCatalogService.class);
        when(catalogService.discover(null)).thenReturn(List.of());
        PlatformModuleWebController controller = new PlatformModuleWebController();
        controller.configureOpenApiCatalog(catalogService);

        assertThat(controller.openApiCatalog()).isEmpty();

        verify(catalogService).discover(null);
    }

    @Test
    void shouldRefreshDynamicRuntimeThroughModuleConfigurationEndpoint() throws Exception {
        PlatformDynamicRuntimeRefreshService refreshService = mock(PlatformDynamicRuntimeRefreshService.class);
        when(refreshService.refresh("crm.contract")).thenReturn(runtimeRefreshResult(false));
        PlatformModuleWebController controller = new PlatformModuleWebController(refreshService);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/platform.module/crm.contract/runtime/refresh"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.module.moduleAlias").value("crm.contract"))
                    .andExpect(jsonPath("$.dryRun").value(false));
        }

        verify(refreshService).refresh("crm.contract");
    }

    @Test
    void shouldExecuteRefreshDynamicRuntimeThroughExplicitEndpoint() throws Exception {
        PlatformDynamicRuntimeRefreshService refreshService = mock(PlatformDynamicRuntimeRefreshService.class);
        when(refreshService.executeRefresh("crm.contract")).thenReturn(runtimeRefreshResult(false));
        PlatformModuleWebController controller = new PlatformModuleWebController(refreshService);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/platform.module/crm.contract/runtime/execute-refresh"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.module.moduleAlias").value("crm.contract"))
                    .andExpect(jsonPath("$.dryRun").value(false));
        }

        verify(refreshService).executeRefresh("crm.contract");
    }

    @Test
    void shouldPreviewRefreshDynamicRuntimeThroughDryRunEndpoint() throws Exception {
        PlatformDynamicRuntimeRefreshService refreshService = mock(PlatformDynamicRuntimeRefreshService.class);
        when(refreshService.previewRefresh("crm.contract")).thenReturn(runtimeRefreshResult(true));
        PlatformModuleWebController controller = new PlatformModuleWebController(refreshService);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/platform.module/crm.contract/runtime/preview-refresh"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.module.moduleAlias").value("crm.contract"))
                    .andExpect(jsonPath("$.dryRun").value(true));
        }

        verify(refreshService).previewRefresh("crm.contract");
    }

    @Test
    void shouldCompileModuleActionsAsNavigatorScopedStandardPageWithoutMenuEntry() {
        PlatformModuleActionWebController controller = new PlatformModuleActionWebController();
        StaticModuleDefinition definition = staticDefinition("platform", PlatformModuleActionService.MODULE_ALIAS,
                "平台模块动作", PlatformModuleAction.class, controller.moduleUiDefinition(),
                java.util.Set.of(net.ximatai.muyun.spring.common.platform.EntityCapability.CRUD,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.ENABLE,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.RECYCLE_BIN,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.SORT));

        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compileModule(definition).uiDescriptor().page();

        assertThat(page.template()).isEqualTo(ModulePageTemplate.FLAT_MANAGEMENT);
        assertThat(page.navigator().levels()).singleElement().satisfies(level -> {
            assertThat(level.key()).isEqualTo("module");
            assertThat(level.sourceModuleAlias()).isEqualTo(PlatformModuleService.MODULE_ALIAS);
        });
        assertThat(page.navigator().contextBindings()).containsExactlyInAnyOrder(
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "module",
                        PageContextTarget.LIST_QUERY, "moduleAlias", null),
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "module",
                        PageContextTarget.FORM_DEFAULT, "moduleAlias", null));
        assertThat(page.detail().display().fields()).extracting(field -> field.fieldRef().fieldName())
                .contains("actionCode", "title", "executorKey", "sourceType", "bindingAlias");
        assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                .contains("moduleAlias", "actionCode", "title", "executorKey", "enabled");
        assertThat(page.detail().editor().fields()).filteredOn(field -> field.fieldRef().fieldName().equals("title"))
                .singleElement().satisfies(field -> assertThat(field.readOnly().constant()).isTrue());
        assertThat(page.detail().editor().fields())
                .filteredOn(field -> field.fieldRef().fieldName().equals("category")
                        || field.fieldRef().fieldName().equals("actionLevel")
                        || field.fieldRef().fieldName().equals("executorType"))
                .allSatisfy(field -> {
                    assertThat(field.option()).isNotNull();
                    assertThat(field.uiType()).isEqualTo("select");
                });
        assertThat(page.detail().editor().fields())
                .filteredOn(field -> field.fieldRef().fieldName().equals("accessModeOverride"))
                .singleElement().satisfies(field -> {
                    assertThat(field.visible().formula()).isNotNull();
                    assertThat(field.readOnly().formula()).isNotNull();
                    assertThat(field.overrideOf()).isEqualTo("accessMode");
                    assertThat(field.option()).isNotNull();
                    assertThat(field.uiType()).isEqualTo("select");
                });
        assertThat(page.detail().editor().fields())
                .filteredOn(field -> field.fieldRef().fieldName().equals("actionAuthOverride"))
                .singleElement().satisfies(field -> {
                    assertThat(field.overrideOf()).isEqualTo("actionAuth");
                    assertThat(field.valueType()).isEqualTo(FieldValueType.BOOLEAN);
                    assertThat(field.uiType()).isEqualTo("switch");
                });
    }

    @Test
    void shouldForceCanonicalModuleActionInsertIntoNavigatorScope() throws Exception {
        PlatformModuleActionService service = mock(PlatformModuleActionService.class);
        PlatformModuleActionWebController controller = new PlatformModuleActionWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        controller.setStandardModuleWebRuntime(platformModuleActionRuntime(controller));

        PlatformModuleAction inserted = action("action-1", "platform.sales.order", "submit");
        when(service.insert(any(PlatformModuleAction.class))).thenReturn("action-1");
        when(service.select("action-1")).thenReturn(inserted);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module_action/insert")
                        .header(PageContextScopePolicy.CONTEXT_HEADER, "{\"module\":\"platform.sales.order\"}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moduleAlias":"other.module","actionCode":"submit","title":"Submit"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.moduleAlias").value("platform.sales.order"));

        ArgumentCaptor<PlatformModuleAction> captor = ArgumentCaptor.forClass(PlatformModuleAction.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getModuleAlias()).isEqualTo("platform.sales.order");
    }

    @Test
    void shouldRegisterCanonicalAndLegacyModuleActionHttpProjectionsWithoutPathConflict() {
        PlatformModuleActionService service = mock(PlatformModuleActionService.class);
        PlatformModuleActionWebController canonical = new PlatformModuleActionWebController();
        PlatformModuleActionLegacyWebController legacy = new PlatformModuleActionLegacyWebController();
        ReflectionTestUtils.setField(canonical, "service", service);
        ReflectionTestUtils.setField(legacy, "service", service);

        assertThatCode(() -> abilityAwareMvc(canonical, legacy)).doesNotThrowAnyException();
    }

    @Test
    void shouldQueryModuleActionsWithinPathModule() throws Exception {
        PlatformModuleActionService service = queryService(mock(PlatformModuleActionService.class));
        PlatformModuleActionLegacyWebController controller = new PlatformModuleActionLegacyWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        PlatformModuleAction action = action("action-1", "platform.sales.order", "submit");
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(action), 1, PageRequest.of(1, 20)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/actions/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"actionCode","values":["submit"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].moduleAlias").value("platform.sales.order"))
                .andExpect(jsonPath("$.records[0].actionCode").value("submit"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class));
        assertClause(criteria.getValue(), "moduleAlias", "platform.sales.order");
        assertClause(criteria.getValue(), "actionCode", "submit");
    }

    @Test
    void shouldForceActionModuleAliasFromPathOnInsert() throws Exception {
        PlatformModuleActionService service = mock(PlatformModuleActionService.class);
        PlatformModuleActionLegacyWebController controller = new PlatformModuleActionLegacyWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        PlatformModuleAction inserted = action("action-1", "platform.sales.order", "submit");
        when(service.insert(any(PlatformModuleAction.class))).thenReturn("action-1");
        when(service.select("action-1")).thenReturn(inserted);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/actions/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moduleAlias":"other.module","actionCode":"submit","title":"Submit"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.moduleAlias").value("platform.sales.order"));

        ArgumentCaptor<PlatformModuleAction> captor = ArgumentCaptor.forClass(PlatformModuleAction.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getModuleAlias()).isEqualTo("platform.sales.order");
    }

    @Test
    void shouldRejectCrossModuleActionUpdate() {
        PlatformModuleActionService service = mock(PlatformModuleActionService.class);
        PlatformModuleActionLegacyWebController controller = new PlatformModuleActionLegacyWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.select("action-1")).thenReturn(action("action-1", "other.module", "submit"));

        MockHttpServletRequest request = requestVars(Map.of("moduleAlias", "platform.sales.order"));

        assertThatThrownBy(() -> controller.update(request, "action-1", new PlatformModuleAction()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to module");
    }

    @Test
    void shouldRejectRelationFieldWhenRelationBelongsToOtherModule() {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        ModuleMetadataFieldService fieldService = mock(ModuleMetadataFieldService.class);
        PlatformModuleMetadataFieldWebController controller =
                new PlatformModuleMetadataFieldWebController(relationService);
        ReflectionTestUtils.setField(controller, "service", fieldService);

        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId("rel-1");
        relation.setModuleAlias("other.module");
        when(relationService.select("rel-1")).thenReturn(relation);

        MockHttpServletRequest request = requestVars(Map.of(
                "moduleAlias", "platform.sales.order",
                "relationId", "rel-1"));

        assertThatThrownBy(() -> controller.ensure(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to module");
    }

    @Test
    void shouldNotExposeFieldCapabilityPrepareEndpoints() {
        List<String> paths = Stream.concat(
                        Stream.of(PlatformModuleMetadataFieldWebController.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class))
                                .filter(Objects::nonNull)
                                .flatMap(this::mappingValues),
                        Stream.of(PlatformModuleMetadataFieldWebController.class.getMethods())
                                .flatMap(this::mappingValues)
                )
                .toList();

        assertThat(paths)
                .noneMatch(path -> path.contains("measure-unit/prepare")
                        || path.contains("money/prepare")
                        || path.contains("/capabilities/")
                        || path.contains("{capability}"));
    }

    @Test
    void shouldForceReferenceConfigFieldFromPathOnInsert() throws Exception {
        MetadataFieldService fieldService = mock(MetadataFieldService.class);
        MetadataFieldReferenceConfigService service = mock(MetadataFieldReferenceConfigService.class);
        MetadataFieldReferenceConfigWebController controller =
                new MetadataFieldReferenceConfigWebController(fieldService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(fieldService.select("field-1")).thenReturn(metadataField("field-1", "metadata-1"));
        MetadataFieldReferenceConfig inserted = new MetadataFieldReferenceConfig();
        inserted.setId("ref-1");
        inserted.setMetadataFieldId("field-1");
        inserted.setTargetMetadataId("target-metadata");
        when(service.insert(any(MetadataFieldReferenceConfig.class))).thenReturn("ref-1");
        when(service.select("ref-1")).thenReturn(inserted);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.metadata/metadata-1/fields/field-1/reference-configs/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"metadataFieldId":"other-field","targetMetadataId":"target-metadata"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metadataFieldId").value("field-1"));

        ArgumentCaptor<MetadataFieldReferenceConfig> captor =
                ArgumentCaptor.forClass(MetadataFieldReferenceConfig.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getMetadataFieldId()).isEqualTo("field-1");
    }

    @Test
    void shouldRejectProtectionConfigWhenFieldBelongsToOtherMetadata() {
        MetadataFieldService fieldService = mock(MetadataFieldService.class);
        MetadataFieldProtectionConfigService service = mock(MetadataFieldProtectionConfigService.class);
        MetadataFieldProtectionConfigWebController controller =
                new MetadataFieldProtectionConfigWebController(fieldService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(fieldService.select("field-1")).thenReturn(metadataField("field-1", "other-metadata"));
        MockHttpServletRequest request = requestVars(Map.of(
                "metadataId", "metadata-1",
                "fieldId", "field-1"));

        assertThatThrownBy(() -> controller.insert(request, new MetadataFieldProtectionConfig()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to metadata");
    }

    @Test
    void shouldRejectCrossFieldProtectionConfigView() {
        MetadataFieldService fieldService = mock(MetadataFieldService.class);
        MetadataFieldProtectionConfigService service = mock(MetadataFieldProtectionConfigService.class);
        MetadataFieldProtectionConfigWebController controller =
                new MetadataFieldProtectionConfigWebController(fieldService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(fieldService.select("field-1")).thenReturn(metadataField("field-1", "metadata-1"));
        MetadataFieldProtectionConfig config = new MetadataFieldProtectionConfig();
        config.setId("protect-1");
        config.setMetadataFieldId("other-field");
        when(service.select("protect-1")).thenReturn(config);
        MockHttpServletRequest request = requestVars(Map.of(
                "metadataId", "metadata-1",
                "fieldId", "field-1"));

        assertThatThrownBy(() -> controller.view(request, "protect-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to field");
    }

    @Test
    void shouldQueryModuleFieldFiltersWithinPathField() throws Exception {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        ModuleMetadataFieldService fieldService = mock(ModuleMetadataFieldService.class);
        ModuleMetadataFieldFilterService service = queryService(mock(ModuleMetadataFieldFilterService.class));
        PlatformModuleMetadataFieldFilterWebController controller =
                new PlatformModuleMetadataFieldFilterWebController(relationService, fieldService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "platform.sales.order"));
        when(fieldService.select("field-1")).thenReturn(moduleField("field-1", "rel-1"));
        ModuleMetadataFieldFilter filter = new ModuleMetadataFieldFilter();
        filter.setId("filter-1");
        filter.setModuleMetadataFieldId("field-1");
        filter.setFormFieldId("field-form");
        filter.setReferenceFieldId("field-ref");
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(filter), 1, PageRequest.of(1, 20)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/metadata-relations/rel-1/fields/field-1/filters/query"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].moduleMetadataFieldId").value("field-1"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class));
        assertClause(criteria.getValue(), "moduleMetadataFieldId", "field-1");
    }

    @Test
    void shouldForceModuleFieldAffectOwnerFromPathOnInsert() throws Exception {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        ModuleMetadataFieldService fieldService = mock(ModuleMetadataFieldService.class);
        ModuleMetadataFieldAffectService service = mock(ModuleMetadataFieldAffectService.class);
        PlatformModuleMetadataFieldAffectWebController controller =
                new PlatformModuleMetadataFieldAffectWebController(relationService, fieldService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "platform.sales.order"));
        when(fieldService.select("field-1")).thenReturn(moduleField("field-1", "rel-1"));
        ModuleMetadataFieldAffect inserted = new ModuleMetadataFieldAffect();
        inserted.setId("affect-1");
        inserted.setModuleMetadataFieldId("field-1");
        when(service.insert(any(ModuleMetadataFieldAffect.class))).thenReturn("affect-1");
        when(service.select("affect-1")).thenReturn(inserted);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/metadata-relations/rel-1/fields/field-1/affects/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moduleMetadataFieldId":"other-field","referenceFieldId":"ref","targetFieldId":"target"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.moduleMetadataFieldId").value("field-1"));

        ArgumentCaptor<ModuleMetadataFieldAffect> captor = ArgumentCaptor.forClass(ModuleMetadataFieldAffect.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getModuleMetadataFieldId()).isEqualTo("field-1");
    }

    @Test
    void shouldForceFormulaRuleRelationFromPathOnInsert() throws Exception {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        ModuleMetadataFormulaRuleService service = mock(ModuleMetadataFormulaRuleService.class);
        PlatformModuleMetadataFormulaRuleWebController controller =
                new PlatformModuleMetadataFormulaRuleWebController(relationService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "platform.sales.order"));
        ModuleMetadataFormulaRule inserted = new ModuleMetadataFormulaRule();
        inserted.setId("rule-1");
        inserted.setRelationId("rel-1");
        inserted.setAlias("checkAmount");
        when(service.insert(any(ModuleMetadataFormulaRule.class))).thenReturn("rule-1");
        when(service.select("rule-1")).thenReturn(inserted);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/metadata-relations/rel-1/formula-rules/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"relationId":"other-rel","alias":"checkAmount","expression":"amount > 0"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationId").value("rel-1"));

        ArgumentCaptor<ModuleMetadataFormulaRule> captor = ArgumentCaptor.forClass(ModuleMetadataFormulaRule.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getRelationId()).isEqualTo("rel-1");
    }

    @Test
    void shouldRejectCrossRelationFormulaRuleSort() {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        ModuleMetadataFormulaRuleService service = mock(ModuleMetadataFormulaRuleService.class);
        PlatformModuleMetadataFormulaRuleWebController controller =
                new PlatformModuleMetadataFormulaRuleWebController(relationService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "platform.sales.order"));
        ModuleMetadataFormulaRule rule = new ModuleMetadataFormulaRule();
        rule.setId("rule-1");
        rule.setRelationId("other-rel");
        when(service.select("rule-1")).thenReturn(rule);
        MockHttpServletRequest request = requestVars(Map.of(
                "moduleAlias", "platform.sales.order",
                "relationId", "rel-1"));

        assertThatThrownBy(() -> controller.requireRecord(request, PlatformAction.SORT, "rule-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to relation");
    }

    @Test
    void shouldQueryMetadataViewsWithinPathRelation() throws Exception {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataViewService service = queryService(mock(MetadataViewService.class));
        PlatformMetadataViewWebController controller = new PlatformMetadataViewWebController(relationService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "platform.sales.order"));
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(metadataView("view-1", "rel-1", EntityViewType.LIST)),
                        1, PageRequest.of(1, 20)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/metadata-relations/rel-1/views/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"viewType","values":["LIST"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].relationId").value("rel-1"))
                .andExpect(jsonPath("$.records[0].viewType").value("LIST"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class));
        assertClause(criteria.getValue(), "relationId", "rel-1");
        assertClause(criteria.getValue(), "viewType", "LIST");
    }

    @Test
    void shouldForceMetadataViewRelationFromPathOnInsert() throws Exception {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataViewService service = mock(MetadataViewService.class);
        PlatformMetadataViewWebController controller = new PlatformMetadataViewWebController(relationService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "platform.sales.order"));
        when(service.insert(any(MetadataView.class))).thenReturn("view-1");
        when(service.select("view-1")).thenReturn(metadataView("view-1", "rel-1", EntityViewType.FORM));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/metadata-relations/rel-1/views/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"relationId":"other-rel","viewType":"FORM","title":"Form"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationId").value("rel-1"));

        ArgumentCaptor<MetadataView> captor = ArgumentCaptor.forClass(MetadataView.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getRelationId()).isEqualTo("rel-1");
    }

    @Test
    void shouldRejectCrossRelationMetadataViewSort() {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataViewService service = mock(MetadataViewService.class);
        PlatformMetadataViewWebController controller = new PlatformMetadataViewWebController(relationService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "platform.sales.order"));
        when(service.select("view-1")).thenReturn(metadataView("view-1", "other-rel", EntityViewType.LIST));
        MockHttpServletRequest request = requestVars(Map.of(
                "moduleAlias", "platform.sales.order",
                "relationId", "rel-1"));

        assertThatThrownBy(() -> controller.requireRecord(request, PlatformAction.SORT, "view-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to relation");
    }

    @Test
    void shouldRejectMetadataViewWhenRelationBelongsToOtherModule() {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataViewService service = mock(MetadataViewService.class);
        PlatformMetadataViewWebController controller = new PlatformMetadataViewWebController(relationService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "other.module"));
        MockHttpServletRequest request = requestVars(Map.of(
                "moduleAlias", "platform.sales.order",
                "relationId", "rel-1"));

        assertThatThrownBy(() -> controller.query(request, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to module");
    }

    @Test
    void shouldForceMetadataViewFieldOwnerFromPathOnInsert() throws Exception {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataViewService viewService = mock(MetadataViewService.class);
        MetadataViewFieldService service = mock(MetadataViewFieldService.class);
        PlatformMetadataViewFieldWebController controller =
                new PlatformMetadataViewFieldWebController(relationService, viewService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "platform.sales.order"));
        when(viewService.select("view-1")).thenReturn(metadataView("view-1", "rel-1", EntityViewType.LIST));
        when(service.insert(any(MetadataViewField.class))).thenReturn("view-field-1");
        when(service.select("view-field-1")).thenReturn(metadataViewField("view-field-1", "view-1", "field-1"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/metadata-relations/rel-1/views/view-1/fields/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"viewId":"other-view","metadataFieldId":"field-1","title":"Code"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewId").value("view-1"));

        ArgumentCaptor<MetadataViewField> captor = ArgumentCaptor.forClass(MetadataViewField.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getViewId()).isEqualTo("view-1");
    }

    @Test
    void shouldRejectCrossRelationMetadataViewFieldQuery() {
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        MetadataViewService viewService = mock(MetadataViewService.class);
        MetadataViewFieldService service = mock(MetadataViewFieldService.class);
        PlatformMetadataViewFieldWebController controller =
                new PlatformMetadataViewFieldWebController(relationService, viewService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(relationService.select("rel-1")).thenReturn(relation("rel-1", "platform.sales.order"));
        when(viewService.select("view-1")).thenReturn(metadataView("view-1", "other-rel", EntityViewType.LIST));
        MockHttpServletRequest request = requestVars(Map.of(
                "moduleAlias", "platform.sales.order",
                "relationId", "rel-1",
                "viewId", "view-1"));

        assertThatThrownBy(() -> controller.query(request, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to relation");
    }

    @Test
    void shouldExposeFieldTypeDirectory() throws Exception {
        FieldSpecService service = queryService(mock(FieldSpecService.class));
        FieldSpecWebController controller = new FieldSpecWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        FieldSpec fieldType = new FieldSpec();
        fieldType.setId("string");
        fieldType.setAlias("string");
        fieldType.setTitle("String");
        fieldType.setFieldType(FieldType.STRING);
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(fieldType), 1, PageRequest.of(1, 20)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.field_spec/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"alias","values":["string"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].alias").value("string"))
                .andExpect(jsonPath("$.records[0].fieldType").value("STRING"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class));
        assertClause(criteria.getValue(), "alias", "string");
    }

    @Test
    void shouldManageDictionaryCategoriesWithinPathApplication() throws Exception {
        DictionaryCategoryService service = queryService(mock(DictionaryCategoryService.class));
        DictionaryCategoryWebController controller = new DictionaryCategoryWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        DictionaryCategory root = dictionaryCategory("category-1", "platform", "common", null);
        DictionaryCategory child = dictionaryCategory("category-2", "platform", "status", "category-1");
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(root), 1, PageRequest.of(1, 20)));
        when(service.children(any(Criteria.class), eq("root"))).thenReturn(List.of(root));
        when(service.children(any(Criteria.class), eq("category-1"))).thenReturn(List.of(child));
        when(service.children(any(Criteria.class), eq("category-2"))).thenReturn(List.of());
        when(service.insert(any(DictionaryCategory.class))).thenReturn("category-1");
        when(service.select("category-1")).thenReturn(root);

        MockMvc mvc = abilityAwareMvc(controller);
        mvc.perform(post("/platform.application/platform/dictionary-categories/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"alias","values":["common"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].applicationAlias").value("platform"))
                .andExpect(jsonPath("$.records[0].alias").value("common"));
        mvc.perform(get("/platform.application/platform/dictionary-categories/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("category-1"))
                .andExpect(jsonPath("$.records[0].children[0].record.id").value("category-2"));
        mvc.perform(post("/platform.application/platform/dictionary-categories/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationAlias":"other","alias":"common","title":"Common"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationAlias").value("platform"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class), any(Sort.class));
        assertClause(criteria.getValue(), "applicationAlias", "platform");
        assertClause(criteria.getValue(), "alias", "common");
        ArgumentCaptor<DictionaryCategory> captor = ArgumentCaptor.forClass(DictionaryCategory.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getApplicationAlias()).isEqualTo("platform");
    }

    @Test
    void shouldTreatUnselectedDictionaryApplicationNavigatorScopeAsAnEmptyReferenceTree() throws Exception {
        DictionaryCategoryService service = mock(DictionaryCategoryService.class);
        DictionaryCategoryWebController controller = new DictionaryCategoryWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        abilityAwareMvc(controller)
                .perform(post("/platform.dictionary_category/navigator/reference/tree/query")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isEmpty());

        verifyNoInteractions(service);
    }

    @Test
    void shouldUseSystemScopeForNestedDictionaryCategoryRequests() {
        DictionaryCategoryWebController controller = new DictionaryCategoryWebController();

        assertThat(controller.webScope(() -> "resolved")).isEqualTo("resolved");
    }

    @Test
    void shouldRejectCrossApplicationDictionaryCategoryUpdate() {
        DictionaryCategoryService service = mock(DictionaryCategoryService.class);
        DictionaryCategoryWebController controller = new DictionaryCategoryWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.select("category-1")).thenReturn(dictionaryCategory("category-1", "crm", "common", null));

        MockHttpServletRequest request = requestVars(Map.of("applicationAlias", "platform"));

        assertThatThrownBy(() -> controller.update(request, "category-1", new DictionaryCategory()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dictionary category does not belong to application");
    }

    @Test
    void shouldMoveDictionaryCategoryWithTreeSortRequest() throws Exception {
        DictionaryCategoryService service = mock(DictionaryCategoryService.class);
        DictionaryCategoryWebController controller = new DictionaryCategoryWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.select("category-1")).thenReturn(dictionaryCategory("category-1", "platform", "common", null));

        MockMvc mvc = abilityAwareMvc(controller);
        mvc.perform(post("/platform.dictionary_category/sort/category-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":"root","scope":{"externalQueryValues":{"applicationAlias":"platform"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).moveInTree(criteria.capture(), eq("category-1"), eq(null), eq(null), eq("root"));
        assertClause(criteria.getValue(), "applicationAlias", "platform");
    }

    @Test
    void shouldRejectStandardSortWhenActionPolicyDoesNotGrantSort() throws Exception {
        DictionaryCategoryService service = mock(DictionaryCategoryService.class);
        DictionaryCategoryWebController controller = new DictionaryCategoryWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService policy = context -> {
            if (PlatformAction.SORT.code().equals(context.actionCode())) {
                throw new PlatformException("ACTION_NOT_AUTHORIZED", 403, "sort forbidden");
            }
        };

        RegisteredWebEndpointCatalog endpoints = new RegisteredWebEndpointCatalog();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomHandlerMapping(() -> new AbilityAwareHandlerMapping(applicationObjectMapper(), endpoints))
                .addInterceptors(new ActionEndpointInterceptor(policy, new ActionEndpointContextResolver(), null, endpoints))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();
        mvc.perform(post("/platform.dictionary_category/navigator/reference/tree/query")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/platform.dictionary_category/sort/category-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"root\"}"))
                .andExpect(status().isForbidden());

        verify(service, org.mockito.Mockito.never()).moveInTree(any(Criteria.class), any(), any(), any(), any());
    }

    @Test
    void shouldManageDictionaryItemsWithinPathCategory() throws Exception {
        DictionaryItemService service = queryService(mock(DictionaryItemService.class));
        DictionaryItemWebController controller = new DictionaryItemWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        DictionaryCategory category = dictionaryCategory("category-1", "platform", "status", null);
        DictionaryItem root = dictionaryItem("item-1", "category-1", "status", "enabled", null);
        DictionaryItem child = dictionaryItem("item-2", "category-1", "status", "active", "item-1");
        when(service.category("platform", "status")).thenReturn(category);
        when(service.category("category-1")).thenReturn(category);
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(root), 1, PageRequest.of(1, 20)));
        when(service.children(any(Criteria.class), eq("root"))).thenReturn(List.of(root));
        when(service.children(any(Criteria.class), eq("item-1"))).thenReturn(List.of(child));
        when(service.children(any(Criteria.class), eq("item-2"))).thenReturn(List.of());
        when(service.insert(any(DictionaryItem.class))).thenReturn("item-1");
        when(service.select("item-1")).thenReturn(root);
        when(service.selectInScope(any(Criteria.class), eq("item-1"))).thenReturn(root);
        when(service.selectInScope(any(Criteria.class), eq("item-2"))).thenReturn(child);

        MockMvc mvc = abilityAwareMvc(controller);
        mvc.perform(post("/platform.application/platform/dictionary-categories/status/items/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"code","values":["enabled"]}]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].categoryId").value("category-1"))
                .andExpect(jsonPath("$.records[0].categoryAlias").value("status"))
                .andExpect(jsonPath("$.records[0].code").value("enabled"));
        mvc.perform(get("/platform.application/platform/dictionary-categories/status/items/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("item-1"))
                .andExpect(jsonPath("$.records[0].children[0].record.id").value("item-2"));
        mvc.perform(post("/platform.application/platform/dictionary-categories/status/items/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationAlias":"other","categoryAlias":"other","code":"enabled","title":"Enabled"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value("category-1"))
                .andExpect(jsonPath("$.categoryAlias").value("status"));
        mvc.perform(get("/platform.dictionary_category/categories/category-1/items/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("item-1"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class), any(Sort.class));
        assertClause(criteria.getValue(), "categoryId", "category-1");
        assertClause(criteria.getValue(), "code", "enabled");
        ArgumentCaptor<DictionaryItem> captor = ArgumentCaptor.forClass(DictionaryItem.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getCategoryId()).isEqualTo("category-1");
        assertThat(captor.getValue().getCategoryAlias()).isEqualTo("status");
    }

    @Test
    void shouldRejectCrossCategoryDictionaryItemUpdate() {
        DictionaryItemService service = mock(DictionaryItemService.class);
        DictionaryItemWebController controller = new DictionaryItemWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.category("platform", "status"))
                .thenReturn(dictionaryCategory("category-1", "platform", "status", null));
        when(service.select("item-1")).thenReturn(dictionaryItem("item-1", "category-2", "priority", "enabled", null));

        MockHttpServletRequest request = requestVars(Map.of(
                "applicationAlias", "platform",
                "categoryAlias", "status"));

        assertThatThrownBy(() -> controller.update(request, "item-1", new DictionaryItem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dictionary item does not belong to category");
    }

    @Test
    void shouldMoveDictionaryItemWithTreeSortRequest() throws Exception {
        DictionaryItemService service = mock(DictionaryItemService.class);
        DictionaryItemWebController controller = new DictionaryItemWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.category("platform", "status"))
                .thenReturn(dictionaryCategory("category-1", "platform", "status", null));
        when(service.select("item-1")).thenReturn(dictionaryItem("item-1", "category-1", "status", "enabled", null));
        when(service.select("item-0")).thenReturn(dictionaryItem("item-0", "category-1", "status", "disabled", "parent-1"));
        when(service.select("parent-1")).thenReturn(dictionaryItem("parent-1", "category-1", "status", "group", null));

        MockMvc mvc = abilityAwareMvc(controller);
        mvc.perform(post("/platform.application/platform/dictionary-categories/status/items/sort/item-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"previousId":"item-0","parentId":"parent-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).moveInTree(criteria.capture(), eq("item-1"), eq("item-0"), eq(null), eq("parent-1"));
        assertClause(criteria.getValue(), "categoryId", "category-1");
    }

    @Test
    void shouldQueryUiSetsWithinPathModule() throws Exception {
        PlatformUiSetService service = queryService(mock(PlatformUiSetService.class));
        PlatformUiSetWebController controller = new PlatformUiSetWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        PlatformUiSet uiSet = uiSet("ui-set-1", "platform.sales.order", "list");
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(uiSet), 1, PageRequest.of(1, 20)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/ui-sets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"alias","values":["list"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].moduleAlias").value("platform.sales.order"))
                .andExpect(jsonPath("$.records[0].alias").value("list"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class), any(Sort.class));
        assertClause(criteria.getValue(), "moduleAlias", "platform.sales.order");
        assertClause(criteria.getValue(), "alias", "list");
    }

    @Test
    void shouldForceUiSetModuleAliasFromPathOnInsert() throws Exception {
        PlatformUiSetService service = mock(PlatformUiSetService.class);
        PlatformUiSetWebController controller = new PlatformUiSetWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        PlatformUiSet inserted = uiSet("ui-set-1", "platform.sales.order", "list");
        when(service.insert(any(PlatformUiSet.class))).thenReturn("ui-set-1");
        when(service.select("ui-set-1")).thenReturn(inserted);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/ui-sets/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moduleAlias":"other.module","alias":"list","setType":"LIST","title":"List"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.moduleAlias").value("platform.sales.order"));

        ArgumentCaptor<PlatformUiSet> captor = ArgumentCaptor.forClass(PlatformUiSet.class);
        verify(service).insert(captor.capture());
        assertThat(captor.getValue().getModuleAlias()).isEqualTo("platform.sales.order");
    }

    @Test
    void shouldQueryTemplatesWithinPathModule() throws Exception {
        PlatformQueryTemplateService service = queryService(mock(PlatformQueryTemplateService.class));
        PlatformQueryTemplateWebController controller = new PlatformQueryTemplateWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        PlatformQueryTemplate template = queryTemplate("query-1", "platform.sales.order", "default");
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(template), 1, PageRequest.of(1, 20)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/platform.sales.order/query-templates/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"alias","values":["default"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].moduleAlias").value("platform.sales.order"))
                .andExpect(jsonPath("$.records[0].alias").value("default"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class), any(Sort.class));
        assertClause(criteria.getValue(), "moduleAlias", "platform.sales.order");
        assertClause(criteria.getValue(), "alias", "default");
    }

    @Test
    void shouldManageFieldUiControlAttributesWithinPathUiType() throws Exception {
        FieldUiControlPropertyService service = queryService(mock(FieldUiControlPropertyService.class));
        FieldUiControlPropertyWebController controller = new FieldUiControlPropertyWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        FieldUiControlProperty attribute = fieldUiTypeAttribute("attr-1", "text", "placeholder");
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(attribute), 1, PageRequest.of(1, 20)));
        when(service.insert(any(FieldUiControlProperty.class))).thenReturn("attr-1");
        when(service.select("attr-1")).thenReturn(attribute);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.field_ui_control/text/properties/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"attributeAlias","values":["placeholder"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].fieldUiControlAlias").value("text"))
                .andExpect(jsonPath("$.records[0].attributeAlias").value("placeholder"));
        mvc.perform(post("/platform.field_ui_control/text/properties/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fieldUiControlAlias":"other","attributeAlias":"placeholder","title":"Placeholder"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldUiControlAlias").value("text"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class), any(Sort.class));
        assertClause(criteria.getValue(), "fieldUiControlAlias", "text");
        assertClause(criteria.getValue(), "attributeAlias", "placeholder");
        ArgumentCaptor<FieldUiControlProperty> attributeCaptor =
                ArgumentCaptor.forClass(FieldUiControlProperty.class);
        verify(service).insert(attributeCaptor.capture());
        assertThat(attributeCaptor.getValue().getFieldUiControlAlias()).isEqualTo("text");
    }

    @Test
    void shouldRejectCrossFieldUiControlAttributeUpdate() {
        FieldUiControlPropertyService service = mock(FieldUiControlPropertyService.class);
        FieldUiControlPropertyWebController controller = new FieldUiControlPropertyWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.select("attr-1")).thenReturn(fieldUiTypeAttribute("attr-1", "number", "placeholder"));

        MockHttpServletRequest request = requestVars(Map.of("fieldUiControlAlias", "text"));

        assertThatThrownBy(() -> controller.update(request, "attr-1", new FieldUiControlProperty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field UI control property does not belong to field UI control");
    }

    @Test
    void shouldManageFieldUiControlMappingsWithinPathUiType() throws Exception {
        FieldUiControlBindingService service = queryService(mock(FieldUiControlBindingService.class));
        FieldUiControlBindingWebController controller = new FieldUiControlBindingWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        FieldUiControlBinding mapping = fieldUiTypeMapping("mapping-1", "select", "options");
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(mapping), 1, PageRequest.of(1, 20)));
        when(service.insert(any(FieldUiControlBinding.class))).thenReturn("mapping-1");
        when(service.select("mapping-1")).thenReturn(mapping);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.field_ui_control/select/bindings/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"valueKey","values":["options"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].fieldUiControlAlias").value("select"))
                .andExpect(jsonPath("$.records[0].valueKey").value("options"));
        mvc.perform(post("/platform.field_ui_control/select/bindings/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fieldUiControlAlias":"other","valueKey":"options","valueFieldSpecAlias":"string","title":"Options"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldUiControlAlias").value("select"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort.class), any(Sort.class));
        assertClause(criteria.getValue(), "fieldUiControlAlias", "select");
        assertClause(criteria.getValue(), "valueKey", "options");
        ArgumentCaptor<FieldUiControlBinding> mappingCaptor =
                ArgumentCaptor.forClass(FieldUiControlBinding.class);
        verify(service).insert(mappingCaptor.capture());
        assertThat(mappingCaptor.getValue().getFieldUiControlAlias()).isEqualTo("select");
    }

    @Test
    void shouldRejectCrossFieldUiControlMappingUpdate() {
        FieldUiControlBindingService service = mock(FieldUiControlBindingService.class);
        FieldUiControlBindingWebController controller = new FieldUiControlBindingWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.select("mapping-1")).thenReturn(fieldUiTypeMapping("mapping-1", "radio", "options"));

        MockHttpServletRequest request = requestVars(Map.of("fieldUiControlAlias", "select"));

        assertThatThrownBy(() -> controller.update(request, "mapping-1", new FieldUiControlBinding()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field UI control binding does not belong to field UI control");
    }

    @Test
    void shouldPublishUiConfigThroughPublishService() throws Exception {
        PlatformPageConfigPublishService service = mock(PlatformPageConfigPublishService.class);
        PlatformPageConfigPublishWebController controller = new PlatformPageConfigPublishWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.page_config_publish/ui-configs/ui-config-1/publish").param("version", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        verify(service).publishUiConfig("ui-config-1", 7);
    }

    @Test
    void shouldWrapPagePublishBusinessMutationResults() throws Exception {
        PlatformPageConfigPublishService service = mock(PlatformPageConfigPublishService.class);
        PlatformPageConfigPublishWebController controller = new PlatformPageConfigPublishWebController();
        ReflectionTestUtils.setField(controller, "service", service);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ActionEndpointInterceptor(new AllowAllActionExecutionPolicyService(),
                        new ActionEndpointContextResolver()))
                .addInterceptors(new BusinessMutationInterceptor())
                .setControllerAdvice(new ActionResultResponseAdvice(PlatformConfigurationWebControllerTest::moduleAlias,
                        new com.fasterxml.jackson.databind.ObjectMapper()))
                .build();

        assertPagePublishActionResult(mvc, "/platform.page_config_publish/ui-configs/ui-config-1/publish",
                "platform.ui-config.published", "UI 配置已发布", "platform.ui_config", "ui-config-1");
        assertPagePublishActionResult(mvc, "/platform.page_config_publish/ui-configs/ui-config-1/unpublish",
                "platform.ui-config.unpublished", "UI 配置已取消发布", "platform.ui_config", "ui-config-1");
        assertPagePublishActionResult(mvc, "/platform.page_config_publish/query-templates/query-template-1/publish",
                "platform.query-template.published", "查询模板已发布",
                "platform.query_template", "query-template-1");
        assertPagePublishActionResult(mvc, "/platform.page_config_publish/query-templates/query-template-1/unpublish",
                "platform.query-template.unpublished", "查询模板已取消发布",
                "platform.query_template", "query-template-1");

        verify(service).publishUiConfig("ui-config-1", null);
        verify(service).unpublishUiConfig("ui-config-1", null);
        verify(service).publishQueryTemplate("query-template-1");
        verify(service).unpublishQueryTemplate("query-template-1");
    }

    @Test
    void shouldReturnActionMessageWhenPagePublishBusinessRuleFails() throws Exception {
        PlatformPageConfigPublishService service = mock(PlatformPageConfigPublishService.class);
        PlatformPageConfigPublishWebController controller = new PlatformPageConfigPublishWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        doThrow(new BusinessException("platform.ui-config.publish-no-visible-field",
                "UI config publish requires at least one visible field: ui-config-1"))
                .when(service).publishUiConfig("ui-config-1", null);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(post("/platform.page_config_publish/ui-configs/ui-config-1/publish"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("platform.ui-config.publish-no-visible-field"))
                .andExpect(jsonPath("$.message")
                        .value("UI config publish requires at least one visible field: ui-config-1"))
                .andExpect(jsonPath("$.actionMessage.code")
                        .value("platform.ui-config.publish-no-visible-field"))
                .andExpect(jsonPath("$.actionMessage.text")
                        .value("UI config publish requires at least one visible field: ui-config-1"))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"));
    }

    private void assertPagePublishActionResult(MockMvc mvc,
                                               String path,
                                               String messageCode,
                                               String messageText,
                                               String moduleAlias,
                                               String recordId) throws Exception {
        mvc.perform(post(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1))
                .andExpect(jsonPath("$.message.code").value(messageCode))
                .andExpect(jsonPath("$.message.text").value(messageText))
                .andExpect(jsonPath("$.changes[?(@.type == 'record-updated' && @.moduleAlias == '%s' && @.recordId == '%s')]"
                        .formatted(moduleAlias, recordId)).exists());
    }

    private DynamicModuleRefreshResult runtimeRefreshResult(boolean dryRun) {
        return new DynamicModuleRefreshResult(
                new ModuleDefinition("crm.contract", "Contract", List.of()),
                Map.of(),
                dryRun);
    }

    private static String moduleAlias(Class<?> moduleType) {
        try {
            Object value = moduleType.getField("MODULE_ALIAS").get(null);
            if (value instanceof String alias) {
                return alias;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to a clear test failure.
        }
        throw new IllegalArgumentException("missing MODULE_ALIAS: " + moduleType.getName());
    }

    private PlatformModule module(String id, String applicationAlias, String parentId) {
        PlatformModule module = new PlatformModule();
        module.setId(id);
        module.setApplicationAlias(applicationAlias);
        module.setParentId(parentId);
        return module;
    }

    private PlatformModuleAction action(String id, String moduleAlias, String actionCode) {
        PlatformModuleAction action = new PlatformModuleAction();
        action.setId(id);
        action.setModuleAlias(moduleAlias);
        action.setActionCode(actionCode);
        action.setTitle(actionCode);
        return action;
    }

    private PlatformUiSet uiSet(String id, String moduleAlias, String alias) {
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId(id);
        uiSet.setModuleAlias(moduleAlias);
        uiSet.setAlias(alias);
        uiSet.setSetType(PlatformUiSetType.LIST);
        uiSet.setTitle(alias);
        return uiSet;
    }

    private PlatformQueryTemplate queryTemplate(String id, String moduleAlias, String alias) {
        PlatformQueryTemplate template = new PlatformQueryTemplate();
        template.setId(id);
        template.setModuleAlias(moduleAlias);
        template.setAlias(alias);
        template.setTitle(alias);
        return template;
    }

    private FieldUiControlProperty fieldUiTypeAttribute(String id, String fieldUiControlAlias, String attributeAlias) {
        FieldUiControlProperty attribute = new FieldUiControlProperty();
        attribute.setId(id);
        attribute.setFieldUiControlAlias(fieldUiControlAlias);
        attribute.setAttributeAlias(attributeAlias);
        attribute.setTitle(attributeAlias);
        return attribute;
    }

    private FieldUiControlBinding fieldUiTypeMapping(String id, String fieldUiControlAlias, String valueKey) {
        FieldUiControlBinding mapping = new FieldUiControlBinding();
        mapping.setId(id);
        mapping.setFieldUiControlAlias(fieldUiControlAlias);
        mapping.setValueKey(valueKey);
        mapping.setValueFieldSpecAlias("string");
        mapping.setTitle(valueKey);
        return mapping;
    }

    private DictionaryCategory dictionaryCategory(String id, String applicationAlias, String alias, String parentId) {
        DictionaryCategory category = new DictionaryCategory();
        category.setId(id);
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        category.setParentId(parentId);
        category.setTitle(alias);
        return category;
    }

    private DictionaryItem dictionaryItem(String id, String categoryId, String categoryAlias,
                                          String code, String parentId) {
        DictionaryItem item = new DictionaryItem();
        item.setId(id);
        item.setCategoryId(categoryId);
        item.setCategoryAlias(categoryAlias);
        item.setCode(code);
        item.setParentId(parentId);
        item.setTitle(code);
        return item;
    }

    private MetadataField metadataField(String id, String metadataId) {
        MetadataField field = new MetadataField();
        field.setId(id);
        field.setMetadataId(metadataId);
        return field;
    }

    private ModuleMetadataRelation relation(String id, String moduleAlias) {
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId(id);
        relation.setModuleAlias(moduleAlias);
        return relation;
    }

    private ModuleMetadataField moduleField(String id, String relationId) {
        ModuleMetadataField field = new ModuleMetadataField();
        field.setId(id);
        field.setRelationId(relationId);
        return field;
    }

    private MetadataView metadataView(String id, String relationId, EntityViewType viewType) {
        MetadataView view = new MetadataView();
        view.setId(id);
        view.setRelationId(relationId);
        view.setViewType(viewType);
        view.setTitle(viewType.name());
        return view;
    }

    private MetadataViewField metadataViewField(String id, String viewId, String metadataFieldId) {
        MetadataViewField field = new MetadataViewField();
        field.setId(id);
        field.setViewId(viewId);
        field.setMetadataFieldId(metadataFieldId);
        field.setTitle(metadataFieldId);
        return field;
    }

    private void assertClause(Criteria criteria, String field, Object value) {
        CriteriaClause clause = clauses(criteria).stream()
                .filter(item -> field.equals(item.getField()))
                .findFirst()
                .orElseThrow();
        assertThat(clause.getValues()).containsExactly(value);
    }

    private List<CriteriaClause> clauses(Criteria criteria) {
        List<CriteriaClause> result = new ArrayList<>();
        collect(criteria.getRoot(), result);
        return result;
    }

    private void collect(CriteriaGroup group, List<CriteriaClause> result) {
        for (CriteriaGroup.Entry entry : group.getEntries()) {
            Object node = criteriaNode(entry);
            if (node instanceof CriteriaClause clause) {
                result.add(clause);
            } else if (node instanceof CriteriaGroup childGroup) {
                collect(childGroup, result);
            }
        }
    }

    private Object criteriaNode(CriteriaGroup.Entry entry) {
        try {
            Method method = entry.getClass().getMethod("getNode");
            return method.invoke(entry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read criteria node", e);
        }
    }

    private StandardModuleWebRuntime platformModuleRuntime(PlatformModuleWebController controller) {
        StaticModuleDefinition module = staticDefinition("platform", PlatformModuleService.MODULE_ALIAS, "模块",
                PlatformModule.class, controller.moduleUiDefinition(),
                java.util.Set.of(net.ximatai.muyun.spring.common.platform.EntityCapability.CRUD,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.TREE,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.ENABLE));
        ApplicationWebController applicationController = new ApplicationWebController();
        StaticModuleDefinition application = staticDefinition("platform", ApplicationService.MODULE_ALIAS, "应用",
                Application.class, applicationController.moduleUiDefinition(),
                java.util.Set.of(net.ximatai.muyun.spring.common.platform.EntityCapability.CRUD,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.ENABLE,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.RECYCLE_BIN))
                .toBuilder().build();
        StaticModuleDefinitionCatalog catalog = new StaticModuleDefinitionCatalog(List.of(module, application));
        return new StandardModuleWebRuntime(new ModuleExecutionPlanCatalog(catalog),
                new StaticRecordReadProjectionService(catalog));
    }

    private StandardModuleWebRuntime platformModuleActionRuntime(PlatformModuleActionWebController controller) {
        StaticModuleDefinition action = staticDefinition("platform", PlatformModuleActionService.MODULE_ALIAS,
                "平台模块动作", PlatformModuleAction.class, controller.moduleUiDefinition(),
                java.util.Set.of(net.ximatai.muyun.spring.common.platform.EntityCapability.CRUD,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.ENABLE,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.RECYCLE_BIN,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.SORT));
        StaticModuleDefinition module = staticDefinition("platform", PlatformModuleService.MODULE_ALIAS, "模块",
                PlatformModule.class, null,
                java.util.Set.of(net.ximatai.muyun.spring.common.platform.EntityCapability.CRUD))
                .toBuilder().build();
        StaticModuleDefinitionCatalog catalog = new StaticModuleDefinitionCatalog(List.of(action, module));
        return new StandardModuleWebRuntime(new ModuleExecutionPlanCatalog(catalog),
                new StaticRecordReadProjectionService(catalog));
    }

    private StaticModuleDefinition staticDefinition(String applicationAlias, String moduleAlias, String title,
                                                    Class<?> modelClass, ModuleUiDefinition uiDefinition,
                                                    java.util.Set<net.ximatai.muyun.spring.common.platform.EntityCapability> capabilities) {
        return StaticModuleDefinition.builder(applicationAlias, moduleAlias, title)
                .entry(net.ximatai.muyun.spring.platform.module.ModuleEntryType.ROUTE, "/" + moduleAlias, null)
                .capabilities(capabilities)
                .actions(Arrays.stream(PlatformAction.values())
                        .map(net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition::platformAction).toList())
                .entities(List.of(new StaticEntityDefinitionCompiler().compile(moduleAlias.substring(moduleAlias.lastIndexOf('.') + 1),
                        moduleAlias, modelClass)))
                .modelClass(modelClass)
                .uiDefinition(uiDefinition)
                .build();
    }

    private static final class PlanOnlyPlatformModuleWebController extends PlatformModuleWebController {
        private boolean rejectDefinitionLookup;

        private void rejectDefinitionLookup() {
            rejectDefinitionLookup = true;
        }

        @Override
        public ModuleUiDefinition moduleUiDefinition() {
            if (rejectDefinitionLookup) {
                throw new AssertionError("request runtime must not call moduleUiDefinition");
            }
            return super.moduleUiDefinition();
        }
    }

    private <S extends QueryAbility<?>> S queryService(S service) {
        doCallRealMethod().when(service).queryDescriptor();
        doCallRealMethod().when(service).queryCriteria(any(QueryRequest.class));
        doCallRealMethod().when(service).querySorts(any(QueryRequest.class));
        return service;
    }

    private MockHttpServletRequest requestVars(Map<String, String> variables) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        return request;
    }

    private Stream<String> mappingValues(Method method) {
        return Stream.of(
                        method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class),
                        method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class),
                        method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class),
                        method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class),
                        method.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class)
                )
                .filter(Objects::nonNull)
                .flatMap(this::mappingValues);
    }

    private Stream<String> mappingValues(Annotation annotation) {
        return Stream.concat(annotationStringArray(annotation, "value"), annotationStringArray(annotation, "path"));
    }

    private Stream<String> annotationStringArray(Annotation annotation, String methodName) {
        try {
            Method method = annotation.annotationType().getMethod(methodName);
            return Arrays.stream((String[]) method.invoke(annotation));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to read mapping annotation " + methodName, ex);
        }
    }
}
