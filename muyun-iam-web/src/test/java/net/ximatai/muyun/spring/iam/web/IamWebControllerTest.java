package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.role.RoleGrantableActionResolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaSqlCompiler;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.web.MuYunSpringJacksonConfiguration;
import net.ximatai.muyun.spring.platform.web.StaticRecordReadProjectionService;
import net.ximatai.muyun.spring.platform.web.PageContextValue;
import net.ximatai.muyun.spring.platform.web.PageSelectionContextRequest;
import net.ximatai.muyun.spring.platform.web.ResolvedPageSelectionContext;
import net.ximatai.muyun.spring.platform.web.MenuEntryRequestContext;
import net.ximatai.muyun.spring.platform.web.MenuEntryRequestInterceptor;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;
import net.ximatai.muyun.spring.platform.web.ListQuerySummaryContributorCatalog;
import net.ximatai.muyun.spring.platform.web.StandardModuleWebRuntime;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinition;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionCatalog;
import net.ximatai.muyun.spring.platform.web.ModuleUiDescriptorCompiler;
import net.ximatai.muyun.spring.platform.web.ActionEndpointContextResolver;
import net.ximatai.muyun.spring.platform.web.ActionEndpointInterceptor;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.dynamic.metadata.StaticEntityDefinitionCompiler;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.platform.web.endpoint.StaticAbilityWebEndpointRegistrar;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegationService;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionDao;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationDao;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.position.Position;
import net.ximatai.muyun.spring.iam.position.PositionCategory;
import net.ximatai.muyun.spring.iam.position.PositionCategoryDao;
import net.ximatai.muyun.spring.iam.position.PositionCategoryService;
import net.ximatai.muyun.spring.iam.position.PositionDao;
import net.ximatai.muyun.spring.iam.position.PositionService;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrant;
import net.ximatai.muyun.spring.iam.role.DataScopePolicy;
import net.ximatai.muyun.spring.iam.role.EmploymentRoleGrant;
import net.ximatai.muyun.spring.iam.role.GrantableAction;
import net.ximatai.muyun.spring.iam.role.ManagementScopeType;
import net.ximatai.muyun.spring.iam.role.Role;
import net.ximatai.muyun.spring.iam.role.RoleKind;
import net.ximatai.muyun.spring.iam.role.RoleOwnerScopeType;
import net.ximatai.muyun.spring.iam.role.RoleDataScopePolicyCatalog;
import net.ximatai.muyun.spring.iam.role.RolePermissionMatrix;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.role.TenantScopePolicy;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.tenant.TenantDao;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.PasswordHashingService;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountDao;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinActionOutcome;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinItem;
import net.ximatai.muyun.spring.platform.deletion.RestoreReport;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IamWebControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TenantDao tenantDao;
    private OrganizationDao organizationDao;
    private PositionDao positionDao;
    private PositionCategoryDao positionCategoryDao;
    private EmployeePositionDao employeePositionDao;
    private UserAccountDao userAccountDao;
    private RecycleBinFacade recycleBinFacade;
    private TenantService tenantService;
    private RoleService roleService;
    private RoleGrantableActionResolver grantableActionResolver;
    private CurrentUser currentUser;
    private MockMvc mvc;

    @Test
    void shouldUsePositionCategoryToEstablishTenantScopeWithoutASessionMutationConstraint() {
        PositionDao dao = mock(PositionDao.class);
        PositionCategoryService categoryService = mock(PositionCategoryService.class);
        PositionService positionService = new PositionService(dao, mock(ActiveTenantVerifier.class), categoryService,
                mock(EmployeePositionDao.class));
        PlanOnlyPositionWebController controller = new PlanOnlyPositionWebController();
        ReflectionTestUtils.setField(controller, "service", positionService);
        controller.setPositionCategoryService(categoryService);
        ReflectionTestUtils.setField(controller, "standardModuleWebRuntime", positionRuntime(controller));
        controller.rejectDefinitionLookup();

        PositionCategory category = new PositionCategory();
        category.setId("category-1");
        category.setTenantId("tenant_a");
        when(categoryService.select("category-1")).thenReturn(category);
        doAnswer(invocation -> null).when(categoryService).requireEnabled(eq("category-1"), any());
        when(dao.count(any(Criteria.class))).thenReturn(0L);
        when(dao.insert(any(Position.class))).thenAnswer(invocation -> invocation.<Position>getArgument(0).getId());
        when(dao.updateByIdAndCondition(any(Position.class), any())).thenReturn(1);
        when(dao.updateByIdAndVersion(any(Position.class), any())).thenReturn(1);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenAnswer(invocation -> {
            PageRequest page = invocation.getArgument(1);
            if (page.getLimit() > 1) {
                return List.of();
            }
            Position position = new Position();
            position.setId("position-1");
            position.setCategoryId("category-1");
            position.setCode("DEV");
            position.setTitle("Developer");
            position.setTenantId("tenant_a");
            return List.of(position);
        });

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant_a"));
             TenantContext.Scope tenant = TenantContext.use("tenant_a")) {
            Position incoming = position(null, "category-1", "DEV", "Developer");
            Position saved = controller.insert(incoming);
            assertThat(saved.getTenantId()).isEqualTo("tenant_a");
            assertThat(incoming.getTenantId()).isEqualTo("tenant_a");

            Position update = position(null, "category-1", "DEV-2", "Developer II");
            Position updated = controller.update("position-1", update);
            assertThat(updated.getTenantId()).isEqualTo("tenant_a");
            assertThat(update.getTenantId()).isEqualTo("tenant_a");
        }
    }

    @Test
    void shouldExposeTenantBrandingAsAStandardFormGroup() {
        assertThat(((net.ximatai.muyun.spring.platform.web.FlatManagementPageDefinition) new TenantWebController()
                .moduleUiDefinition().page()).detail().editor().fields())
                .extracting(field -> field.fieldRef().fieldName())
                .contains("alias", "title", "workbenchBrandMode", "workbenchTitle", "workbenchSubtitle",
                        "lightLogoAssetId", "darkLogoAssetId");
        assertThat(((net.ximatai.muyun.spring.platform.web.FlatManagementPageDefinition) new TenantWebController()
                .moduleUiDefinition().page()).detail().editor().formGroups())
                .singleElement()
                .satisfies(group -> {
                    assertThat(group.groupCode()).isEqualTo("workbench_branding");
                    assertThat(group.title()).isEqualTo("工作台品牌");
                    assertThat(group.subtitle()).isEqualTo("配置 Logo、主标题与副标题的展示方式。");
                    assertThat(group.fields()).extracting(field -> field.fieldRef().fieldName())
                            .containsExactly("workbenchBrandMode", "workbenchTitle", "workbenchSubtitle",
                                    "lightLogoAssetId", "darkLogoAssetId");
                    assertThat(group.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("workbenchBrandMode"))
                            .singleElement().satisfies(field -> assertThat(field.columnSpan()).isEqualTo(2));
                });
    }

    @Test
    void shouldGiveEmployeeEmailListColumnAReadableWidth() {
        EmployeeWebController controller = new EmployeeWebController(
                mock(EmployeeAccountService.class), mock(EmployeeDelegationService.class));

        var list = ((net.ximatai.muyun.spring.platform.web.ListDetailCardPageDefinition) controller
                .moduleUiDefinition().page()).list();
        assertThat(list.list().fields())
                .filteredOn(field -> field.fieldRef().fieldName().equals("email"))
                .singleElement()
                .satisfies(field -> assertThat(field.width()).isEqualTo("180px"));
        assertThat(list.relationExpansions())
                .singleElement()
                .satisfies(expansion -> {
                    assertThat(expansion.relationCode()).isEqualTo("positions");
                    assertThat(expansion.fields()).containsExactly(
                            "organizationId", "departmentId", "positionId", "primaryPosition", "enabled");
                });
    }

    @Test
    void shouldCompileTenantBrandingControlsIntoTheStandardRuntimeDescriptor() {
        TenantWebController controller = new TenantWebController();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", TenantService.MODULE_ALIAS, "租户管理")
                .modelClass(Tenant.class)
                .entities(List.of(new StaticEntityDefinitionCompiler().compile("tenant", "租户管理", Tenant.class)))
                .uiDefinition(controller.moduleUiDefinition())
                .build();

        var descriptor = ModuleUiDescriptorCompiler.compile(definition);

        assertThat(descriptor.page().detail().editor().fields())
                .filteredOn(field -> field.fieldRef().fieldName().equals("workbenchBrandMode"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.fieldControl().alias()).isEqualTo("select");
                    assertThat(field.option().inlineItems())
                            .extracting(item -> item.code())
                            .containsExactly("logoOnly", "logoWithTitle");
                });
        assertThat(descriptor.fileReferences())
                .extracting(reference -> reference.fieldRef().fieldName())
                .containsExactlyInAnyOrder("lightLogoAssetId", "darkLogoAssetId");
    }

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        objectMapper.registerModule(new MuYunSpringJacksonConfiguration().codeTitleEnumJacksonModule());
        currentUser = null;
        tenantDao = mock(TenantDao.class);
        organizationDao = mock(OrganizationDao.class);
        positionDao = mock(PositionDao.class);
        positionCategoryDao = mock(PositionCategoryDao.class);
        employeePositionDao = mock(EmployeePositionDao.class);
        userAccountDao = mock(UserAccountDao.class);
        recycleBinFacade = mock(RecycleBinFacade.class);
        roleService = mock(RoleService.class);
        when(roleService.select(any())).thenAnswer(invocation -> {
            Role role = new Role();
            role.setId(invocation.getArgument(0));
            return role;
        });
        grantableActionResolver = mock(RoleGrantableActionResolver.class);
        tenantService = new TenantService(tenantDao);
        OrganizationService organizationService = new OrganizationService(organizationDao, tenantService);
        PositionCategoryService positionCategoryService = new PositionCategoryService(
                positionCategoryDao, tenantService, positionDao);
        PositionService positionService = new PositionService(positionDao, tenantService, positionCategoryService,
                employeePositionDao);
        UserAccountService userAccountService = net.ximatai.muyun.spring.iam.support.UserAccountServiceTestFactory.create(
                userAccountDao, tenantService, new PasswordHashingService());
        TenantWebController tenantController = new TenantWebController();
        OrganizationWebController organizationController = new OrganizationWebController();
        PositionWebController positionController = new PositionWebController();
        UserAccountWebController userAccountController = new UserAccountWebController();
        RoleWebController roleController = new RoleWebController(grantableActionResolver);
        RoleScopePageSelectionResolver roleScopeResolver = mock(RoleScopePageSelectionResolver.class);
        when(roleScopeResolver.selectionKind()).thenReturn(RoleScopePageSelectionResolver.SELECTION_KIND);
        when(roleScopeResolver.resolve(any(PageSelectionContextRequest.class))).thenAnswer(invocation -> {
            PageSelectionContextRequest selection = invocation.getArgument(0);
            String key = selection.selectionKey();
            RoleOwnerScopeType type = key.equals("platform") ? RoleOwnerScopeType.PLATFORM
                    : key.startsWith("organization:") ? RoleOwnerScopeType.ORGANIZATION : RoleOwnerScopeType.TENANT;
            String scopeId = type == RoleOwnerScopeType.PLATFORM ? null
                    : key.substring(key.indexOf(':') + 1);
            String tenantId = type == RoleOwnerScopeType.PLATFORM ? null
                    : type == RoleOwnerScopeType.TENANT ? scopeId : "demo";
            return new ResolvedPageSelectionContext(RoleScopePageSelectionResolver.SELECTION_KIND, key, Map.of(
                    "ownerScopeType", PageContextValue.of(type),
                    "ownerScopeId", PageContextValue.of(scopeId),
                    "ownerScopeKey", PageContextValue.of(key),
                    "tenantId", PageContextValue.of(tenantId)
            ));
        });
        ReflectionTestUtils.setField(tenantController, "service", tenantService);
        ReflectionTestUtils.setField(tenantController, "standardModuleWebRuntime", tenantRuntime(tenantController));
        ReflectionTestUtils.setField(organizationController, "service", organizationService);
        ReflectionTestUtils.setField(positionController, "service", positionService);
        ReflectionTestUtils.setField(positionController, "standardModuleWebRuntime", positionRuntime(positionController));
        ReflectionTestUtils.setField(userAccountController, "service", userAccountService);
        ReflectionTestUtils.setField(userAccountController, "standardModuleWebRuntime", userRuntime(userAccountController));
        ReflectionTestUtils.setField(roleController, "service", roleService);
        roleController.setRoleScopeSelectionResolver(roleScopeResolver);
        mvc = MockMvcBuilders
                .standaloneSetup(
                        tenantController,
                        organizationController,
                        positionController,
                        userAccountController,
                        roleController
                )
                .setCustomHandlerMapping(() -> new AbilityAwareHandlerMapping(objectMapper, recycleBinFacade))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.ofNullable(currentUser)))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CurrentUserContext.clear();
    }

    private static final class AbilityAwareHandlerMapping extends RequestMappingHandlerMapping {
        private final ObjectMapper objectMapper;
        private final RecycleBinFacade recycleBinFacade;

        private AbilityAwareHandlerMapping(ObjectMapper objectMapper, RecycleBinFacade recycleBinFacade) {
            this.objectMapper = objectMapper;
            this.recycleBinFacade = recycleBinFacade;
        }

        @Override
        public void afterPropertiesSet() {
            super.afterPropertiesSet();
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            beanFactory.registerSingleton("recycleBinFacade", recycleBinFacade);
            ObjectProvider<RecycleBinFacade> provider = beanFactory.getBeanProvider(RecycleBinFacade.class);
            new StaticAbilityWebEndpointRegistrar(
                    obtainApplicationContext(),
                    this,
                    new RegisteredWebEndpointCatalog(),
                    provider,
                    objectMapper
            ).afterSingletonsInstantiated();
        }
    }

    @Test
    void shouldQueryAndCreateTenantThroughSystemManagedWebContract() throws Exception {
        when(tenantDao.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(tenant("tenant_a", "Tenant A")), 1, PageRequest.of(1, 20)));
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(tenant("tenant_b", "Tenant B")));
        when(tenantDao.insert(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.isSystem()).isTrue();
            return invocation.<Tenant>getArgument(0).getAlias();
        });

        mvc.perform(post("/iam.tenant/query"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].alias").value("tenant_a"))
                .andExpect(jsonPath("$.records[0].title").value("Tenant A"))
                .andExpect(jsonPath("$.pageNum").value(1))
                .andExpect(jsonPath("$.pageSize").value(20));

        mvc.perform(post("/iam.tenant/insert")
                        .contentType("application/json")
                        .content(json(tenant("tenant_b", "Tenant B"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alias").value("tenant_b"));
    }

    @Test
    void shouldExposeTenantRecycleBinThroughSystemScopedLifecycleFacade() throws Exception {
        Tenant deleted = tenant("tenant_deleted", "Deleted Tenant");
        deleted.setDeleted(true);
        deleted.setDeletedAt(java.time.Instant.EPOCH);
        when(tenantDao.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenAnswer(invocation -> {
            assertThat(TenantContext.isSystem()).isTrue();
            return PageResult.of(List.of(deleted), 1, invocation.getArgument(1));
        });
        RecycleBinItem<Tenant> recycleBinItem = new RecycleBinItem<>(deleted, "delete-operation-1",
                deleted.getDeletedAt(), true, false, null);
        when(recycleBinFacade.items(eq(tenantService), any(),
                any(java.util.function.Function.class), any(java.util.function.Function.class)))
                .thenReturn((List) List.of(recycleBinItem));
        RestoreReport report = new RestoreReport("delete-operation-1", "restore-operation-1", List.of());
        when(recycleBinFacade.restoreWithSource(tenantService, "delete-operation-1")).thenAnswer(invocation -> {
            assertThat(TenantContext.isSystem()).isTrue();
            return new RecycleBinActionOutcome<>(deleted.getId(), deleted, report);
        });

        mvc.perform(post("/iam.tenant/recycle-bin/query")
                        .contentType("application/json")
                        .content(json(Map.of("page", Map.of("pageNum", 2, "pageSize", 10)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].sourceDeleteOperationId").value("delete-operation-1"))
                .andExpect(jsonPath("$.records[0].restorable").value(true));

        mvc.perform(post("/iam.tenant/recycle-bin/delete-operation-1/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceOperationId").value("delete-operation-1"))
                .andExpect(jsonPath("$.restoreOperationId").value("restore-operation-1"));

        verify(recycleBinFacade).items(eq(tenantService), any(),
                any(java.util.function.Function.class), any(java.util.function.Function.class));
        verify(recycleBinFacade).restoreWithSource(tenantService, "delete-operation-1");
    }

    @Test
    void shouldRejectUnsupportedStaticQueryConditionsInsteadOfIgnoringThem() throws Exception {
        mvc.perform(post("/iam.tenant/query")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "conditions", List.of(Map.of(
                                        "fieldName", "title",
                                        "operator", "EQ",
                                        "values", List.of("Tenant A")
                                ))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("query conditions are not supported by iam.tenant"));
    }

    @Test
    void shouldRejectUnsupportedStaticQueryCriteriaInsteadOfIgnoringThem() throws Exception {
        mvc.perform(post("/iam.tenant/query")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "criteria", Map.of(
                                        "operator", "OR",
                                        "conditions", List.of(Map.of(
                                                "fieldName", "title",
                                                "operator", "EQ",
                                                "values", List.of("Tenant A")
                                        ))
                                )
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("query criteria are not supported by iam.tenant"));
    }

    @Test
    void shouldRejectUnsupportedIamQuerySurfacesInsteadOfIgnoringThem() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("tenant_a", "Tenant A")));

        mvc.perform(post("/iam.position/query")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "unpaged", true,
                                "externalQueryValues", Map.of("categoryId", "category-1"),
                                "quickSearch", "dev",
                                "quickSearchFields", List.of("categoryId"),
                                "conditions", List.of(Map.of(
                                        "fieldName", "categoryId",
                                        "operator", "EQ",
                                        "values", List.of("category-1")
                                ))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("quick search field is not supported by iam.position: categoryId"));
    }

    @Test
    void shouldQueryPositionsByCategoryCondition() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("tenant_a", "Tenant A")));
        when(positionDao.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(position("pos-1", "category-1", "DEV", "Developer")), 1,
                        PageRequest.of(1, 20)));

        mvc.perform(post("/iam.position/query")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "externalQueryValues", Map.of("categoryId", "category-1"),
                                "conditions", List.of(Map.of(
                                        "fieldName", "categoryId",
                                        "operator", "EQ",
                                        "values", List.of("category-1")
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("pos-1"))
                .andExpect(jsonPath("$.records[0].code").value("DEV"));

        ArgumentCaptor<Criteria> criteriaCaptor = ArgumentCaptor.captor();
        verify(positionDao).pageQuery(criteriaCaptor.capture(), any(PageRequest.class), any(Sort[].class));
        assertThat(containsCondition(criteriaCaptor.getValue(), "categoryId", "category-1")).isTrue();
    }

    @Test
    void shouldQueryPositionsWithoutPagingWhenRequested() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("tenant_a", "Tenant A")));
        when(positionDao.list(any(Criteria.class), any(Sort[].class)))
                .thenReturn(List.of(position("pos-1", "category-1", "DEV", "Developer")));

        mvc.perform(post("/iam.position/query")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "unpaged", true,
                                "externalQueryValues", Map.of("categoryId", "category-1"),
                                "conditions", List.of(Map.of(
                                        "fieldName", "categoryId",
                                        "operator", "EQ",
                                        "values", List.of("category-1")
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("pos-1"))
                .andExpect(jsonPath("$.pageNum").value(1))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.total").value(1));

        ArgumentCaptor<Criteria> criteriaCaptor = ArgumentCaptor.captor();
        verify(positionDao).list(criteriaCaptor.capture(), any(Sort[].class));
        verify(positionDao, never()).pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class));
        assertThat(containsCondition(criteriaCaptor.getValue(), "categoryId", "category-1")).isTrue();
    }

    @Test
    void shouldExposeOrganizationTreeUnderTenantScope() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("tenant_a", "Tenant A")));
        when(organizationDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.currentTenantId()).contains("tenant_a");
                    Organization organization = organization("org-1", "HQ", "Headquarters");
                    organization.setTenantId("tenant_a");
                    organization.setParentId(TreeAbility.ROOT_ID);
                    return List.of(organization);
                })
                .thenReturn(List.of());

        mvc.perform(get("/iam.organization/tree?flat=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("org-1"))
                .andExpect(jsonPath("$.records[0].tenantId").value("tenant_a"))
                .andExpect(jsonPath("$.records[0].code").value("HQ"));
    }

    @Test
    void shouldExposeOrganizationTreeForRequestedTenantUnderSystemScope() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        Organization root = organization("org-1", "HQ", "Headquarters");
        root.setTenantId("demo");
        root.setParentId(TreeAbility.ROOT_ID);
        when(organizationDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(List.of(root))
                .thenReturn(List.of());

        mvc.perform(get("/iam.organization/tree?tenantId=demo&flat=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("org-1"))
                .andExpect(jsonPath("$.records[0].tenantId").value("demo"));

        ArgumentCaptor<Criteria> criteriaCaptor = ArgumentCaptor.captor();
        verify(organizationDao, atLeastOnce())
                .query(criteriaCaptor.capture(), any(PageRequest.class), any(Sort[].class));
        assertThat(criteriaCaptor.getAllValues())
                .anySatisfy(criteria -> assertThat(compiledCriteria(criteria)).contains("\"tenantId\" ="));
    }

    @Test
    void shouldExposeOrganizationTreeWithoutTenantUnderSystemScope() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        Organization root = organization("org-1", "HQ", "Headquarters");
        root.setTenantId("demo");
        root.setParentId(TreeAbility.ROOT_ID);
        when(organizationDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(List.of(root))
                .thenReturn(List.of());

        mvc.perform(get("/iam.organization/tree?flat=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("org-1"))
                .andExpect(jsonPath("$.records[0].tenantId").value("demo"));

        ArgumentCaptor<Criteria> criteriaCaptor = ArgumentCaptor.captor();
        verify(organizationDao, atLeastOnce())
                .query(criteriaCaptor.capture(), any(PageRequest.class), any(Sort[].class));
        assertThat(criteriaCaptor.getAllValues())
                .noneSatisfy(criteria -> assertThat(compiledCriteria(criteria)).contains("\"tenantId\" ="));
    }

    @Test
    void shouldRejectOrganizationTreeForDifferentTenantUnderTenantScope() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("tenant_a", "Tenant A")));

        mvc.perform(get("/iam.organization/tree?tenantId=tenant_b"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("organization tree tenantId must match current tenant"));
    }

    @Test
    void shouldExposeOrganizationNestedTreeByDefault() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("tenant_a", "Tenant A")));
        Organization root = organization("org-1", "HQ", "Headquarters");
        root.setTenantId("tenant_a");
        root.setParentId(TreeAbility.ROOT_ID);
        Organization child = organization("org-2", "BR", "Branch");
        child.setTenantId("tenant_a");
        child.setParentId("org-1");
        when(organizationDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(root), List.of(child));
        when(organizationDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(List.of(root), List.of(root), List.of(child), List.of(child), List.of());

        mvc.perform(get("/iam.organization/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("org-1"))
                .andExpect(jsonPath("$.records[0].record.tenantId").value("tenant_a"))
                .andExpect(jsonPath("$.records[0].children[0].record.id").value("org-2"))
                .andExpect(jsonPath("$.records[0].children[0].children").isArray());
    }

    @Test
    void shouldCreateOrganizationUnderTenantScope() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("tenant_a", "Tenant A")));
        when(organizationDao.insert(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("tenant_a");
            Organization organization = invocation.getArgument(0);
            assertThat(organization.getTenantId()).isEqualTo("tenant_a");
            return "org-1";
        });
        when(organizationDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(), List.of(organization("org-1", "HQ", "Headquarters")));

        mvc.perform(post("/iam.organization/insert")
                        .header("X-MuYun-Page-Context", "{\"tenant\":\"tenant_a\"}")
                        .contentType("application/json")
                        .content(json(organization(null, "HQ", "Headquarters"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("org-1"));
    }

    @Test
    void shouldCreateUserThroughStandardCrudContractWithoutExposingPasswordMaterial() throws Exception {
        currentUser = CurrentUser.tenantUser("admin-1", "Admin", "tenant_a");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("tenant_a", "Tenant A")));
        when(userAccountDao.insert(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("tenant_a");
            UserAccount user = invocation.getArgument(0);
            assertThat(user.getTenantId()).isEqualTo("tenant_a");
            assertThat(user.getPasswordHash()).startsWith("pbkdf2$");
            assertThat(user.getPasswordHash()).isNotEqualTo("client-supplied-hash");
            return "user-1";
        });
        when(userAccountDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(), List.of(), List.of(user("user-1", "alice", "Alice")));

        mvc.perform(post("/iam.user/insert")
                        .header("X-MuYun-Page-Context", "{\"tenant\":\"tenant_a\"}")
                        .contentType("application/json")
                        .content("""
                                {
	                                  "username":"alice",
	                                  "title":"Alice",
	                                  "passwordHash":"client-supplied-hash",
	                                  "password":"secret2"
	                                }
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("user-1"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void shouldCreateTenantUserUnderResolvedMutationTenantForSystemUser() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("demo", "Demo")));
        when(userAccountDao.insert(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("demo");
            UserAccount user = invocation.getArgument(0);
            assertThat(user.getTenantId()).isEqualTo("demo");
            assertThat(user.getPasswordHash()).startsWith("pbkdf2$");
            return "user-1";
        });
        when(userAccountDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(), List.of(), List.of(user("user-1", "alice", "Alice")));

        mvc.perform(post("/iam.user/insert")
                        .header("X-MuYun-Page-Context", "{\"tenant\":\"demo\"}")
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantId":"demo",
                                  "username":"alice",
                                  "title":"Alice",
                                  "password":"secret2"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("user-1"))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void shouldCreateRoleWithCodeTitleEnumCodeThroughStandardCrudContract() throws Exception {
        currentUser = CurrentUser.tenantUser("admin-1", "Admin", "tenant_a");
        Role saved = new Role();
        saved.setId("role-1");
        saved.setTitle("Data Grant Role");
        saved.setRoleKind(RoleKind.DATA_GRANT);
        when(roleService.insert(any())).thenAnswer(invocation -> {
            Role incoming = invocation.getArgument(0);
            assertThat(incoming.getRoleKind()).isEqualTo(RoleKind.DATA_GRANT);
            return "role-1";
        });
        when(roleService.select("role-1")).thenReturn(saved);

        mvc.perform(post("/iam.role/insert")
                        .header("X-MuYun-Page-Selection", roleScopeHeader("tenant:tenant_a"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "title":"Data Grant Role",
                                  "roleKind":"dataGrant"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("role-1"))
                .andExpect(jsonPath("$.roleKind").value("dataGrant"));
    }

    @Test
    void shouldCreateTenantScopedRoleUnderResolvedMutationTenantForSystemUser() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        Role saved = new Role();
        saved.setId("role-1");
        saved.setTenantId("demo");
        saved.setTitle("Organization Role");
        saved.setOwnerScopeType(RoleOwnerScopeType.ORGANIZATION);
        saved.setOwnerScopeId("demo_org");
        when(roleService.insert(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("demo");
            Role incoming = invocation.getArgument(0);
            assertThat(incoming.getTenantId()).isEqualTo("demo");
            assertThat(incoming.getOwnerScopeType()).isEqualTo(RoleOwnerScopeType.ORGANIZATION);
            return "role-1";
        });
        when(roleService.select("role-1")).thenReturn(saved);

        mvc.perform(post("/iam.role/insert")
                        .header("X-MuYun-Page-Selection", roleScopeHeader("organization:demo_org"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantId":"demo",
                                  "title":"Organization Role",
                                  "ownerScopeType":"organization",
                                  "ownerScopeId":"demo_org"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("role-1"))
                .andExpect(jsonPath("$.tenantId").value("demo"));
    }

    @Test
    void shouldCreateRoleThroughActualSelectionResolverAfterActionInterceptorAuthorizesRequest() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        TenantService scopedTenantService = mock(TenantService.class);
        OrganizationService scopedOrganizationService = mock(OrganizationService.class);
        Tenant tenant = new Tenant();
        tenant.setId("demo");
        tenant.setEnabled(true);
        when(scopedTenantService.requireActiveTenant("demo")).thenReturn(tenant);
        Organization organization = new Organization();
        organization.setId("demo_org");
        organization.setTenantId("demo");
        organization.setEnabled(true);
        when(scopedOrganizationService.requireEnabled("demo_org", "role owner organization is not active: demo_org"))
                .thenReturn(organization);

        Role saved = tenantScopedRole("role-1", "demo");
        when(roleService.insert(any())).thenAnswer(invocation -> {
            Role incoming = invocation.getArgument(0);
            assertThat(incoming.getTenantId()).isEqualTo("demo");
            assertThat(incoming.getOwnerScopeType()).isEqualTo(RoleOwnerScopeType.ORGANIZATION);
            assertThat(incoming.getOwnerScopeId()).isEqualTo("demo_org");
            assertThat(incoming.getOwnerScopeKey()).isEqualTo("organization:demo_org");
            return "role-1";
        });
        when(roleService.select("role-1")).thenReturn(saved);
        MockMvc securedMvc = securedRoleMvc(scopedTenantService, scopedOrganizationService);

        securedMvc.perform(post("/iam.role/insert")
                        .header("X-MuYun-Page-Selection", roleScopeHeader("organization:demo_org"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantId":"forged",
                                  "title":"Organization Role",
                                  "ownerScopeType":"platform",
                                  "ownerScopeId":"forged"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("role-1"));
    }

    @Test
    void shouldRejectRoleCreateWithoutTrustedSelectionAfterActionInterceptorAuthorizesRequest() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");

        securedRoleMvc(mock(TenantService.class), mock(OrganizationService.class))
                .perform(post("/iam.role/insert")
                        .contentType("application/json")
                        .content("""
                                {"tenantId":"forged","title":"Organization Role","ownerScopeType":"platform"}
                                """))
                .andExpect(status().isBadRequest());

        verify(roleService, never()).insert(any());
    }

    @Test
    void shouldRejectRoleCreateWhenTheAuthorizedActionContextNoLongerMatchesSelectionRequest() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        HandlerInterceptor mismatchedActionContext = new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response,
                                     Object handler) {
                ActionExecutionContext context = ActionExecutionContext.ofPlatformAction(RoleService.MODULE_ALIAS,
                        PlatformAction.VIEW, Set.of(), Optional.of(currentUser));
                ActionExecutionContextHolder.use(context.withAuthorizationResult(ActionAuthorizationResult.allowed(context)));
                return true;
            }
        };

        securedRoleMvc(mock(TenantService.class), mock(OrganizationService.class), mismatchedActionContext)
                .perform(post("/iam.role/insert")
                        .header("X-MuYun-Page-Selection", roleScopeHeader("organization:demo_org"))
                        .contentType("application/json")
                        .content("""
                                {"title":"Organization Role"}
                                """))
                .andExpect(status().isForbidden());

        verify(roleService, never()).insert(any());
    }

    @Test
    void shouldDeleteTenantScopedRoleUnderResolvedExistingRecordTenantForSystemUser() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        Role existing = new Role();
        existing.setId("role-1");
        existing.setTenantId("demo");
        existing.setTitle("Organization Role");
        existing.setOwnerScopeType(RoleOwnerScopeType.ORGANIZATION);
        existing.setOwnerScopeId("demo_org");
        existing.setOwnerScopeKey("organization:demo_org");
        when(roleService.select("role-1")).thenReturn(existing);
        when(roleService.delete("role-1", 0)).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("demo");
            return 1;
        });

        mvc.perform(post("/iam.role/delete/{id}", "role-1")
                        .header("X-MuYun-Page-Selection", roleScopeHeader("organization:demo_org"))
                        .contentType("application/json")
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
    }

    @Test
    void shouldSortTenantScopedRoleUnderResolvedExistingRecordTenantForSystemUser() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        Role existing = tenantScopedRole("role-1", "demo");
        Role previous = tenantScopedRole("role-0", "demo");
        when(roleService.select("role-1")).thenReturn(existing);
        when(roleService.select("role-0")).thenReturn(previous);
        doAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("demo");
            return null;
        }).when(roleService).moveAfter("role-1", "role-0");

        mvc.perform(post("/iam.role/sort/{id}", "role-1")
                        .header("X-MuYun-Page-Selection", roleScopeHeader("tenant:demo"))
                .contentType("application/json")
                .content("""
                                {"previousId":"role-0"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
    }

    @Test
    void shouldGrantPermissionUnderResolvedRoleTenantForSystemUser() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        Role existing = tenantScopedRole("role-1", "demo");
        when(roleService.select("role-1")).thenReturn(existing);
        when(roleService.grantAction("role-1", "sales.contract", "query",
                DataScopePolicy.OWNER, TenantScopePolicy.CURRENT_TENANT,
                null, null, null)).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("demo");
            return 1;
        });

        mvc.perform(post("/iam.role/grant/{roleId}", "role-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "moduleAlias":"sales.contract",
                                  "actionCode":"query",
                                  "dataScopePolicy":"owner",
                                  "tenantScopePolicy":"currentTenant"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
    }

    @Test
    void shouldKeepExistingPasswordHashWhenUpdatingUserThroughStandardCrudContract() throws Exception {
        currentUser = CurrentUser.tenantUser("admin-1", "Admin", "tenant_a");
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("tenant_a", "Tenant A")));
        UserAccount existing = user("user-1", "alice", "Alice");
        existing.setTenantId("tenant_a");
        existing.setVersion(3);
        existing.setPasswordHash("pbkdf2$existing-hash");
        when(userAccountDao.count(any(Criteria.class))).thenReturn(1L);
        when(userAccountDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(existing));
        when(userAccountDao.updateByIdAndVersion(any(UserAccount.class), any())).thenAnswer(invocation -> {
            UserAccount updated = invocation.getArgument(0);
            assertThat(updated.getPasswordHash()).isEqualTo("pbkdf2$existing-hash");
            assertThat(updated.getPasswordHash()).isNotEqualTo("client-supplied-hash");
            assertThat(updated.getPassword()).isEqualTo("new-plain-password");
            return 1;
        });

        mvc.perform(post("/iam.user/update/{id}", "user-1")
                        .header("X-MuYun-Page-Context", "{\"tenant\":\"tenant_a\"}")
                        .contentType("application/json")
                        .content("""
                                {
                                  "username":"alice",
                                  "title":"Alice Updated",
                                  "passwordHash":"client-supplied-hash",
                                  "password":"new-plain-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void shouldChangeTenantUserPasswordUnderResolvedMutationTenantForSystemUser() throws Exception {
        currentUser = CurrentUser.systemUser("admin", "Admin");
        UserAccount existing = user("user-1", "alice", "Alice");
        existing.setTenantId("demo");
        existing.setPasswordHash(new PasswordHashingService().hash("old-secret"));
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(tenant("demo", "Demo")));
        when(userAccountDao.count(any(Criteria.class))).thenReturn(1L);
        when(userAccountDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(existing));
        when(userAccountDao.updateById(any(UserAccount.class))).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("demo");
            UserAccount updated = invocation.getArgument(0);
            assertThat(new PasswordHashingService().matches("new-secret", updated.getPasswordHash())).isTrue();
            return 1;
        });

        mvc.perform(post("/iam.user/changePassword/{id}", "user-1")
                        .contentType("application/json")
                        .content("""
                                {"password":"new-secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
    }

    @Test
    void shouldViewSystemUserThroughSystemScope() throws Exception {
        currentUser = CurrentUser.systemUser(UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID, "admin");
        UserAccount admin = user(UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID, "admin", "admin");
        admin.setTenantId(null);
        admin.setPasswordHash(new PasswordHashingService().hash("admin123"));
        when(userAccountDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(admin));

        mvc.perform(get("/iam.user/view/{id}", UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void shouldRejectResettingCurrentUserPassword() throws Exception {
        currentUser = CurrentUser.systemUser(UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID, "admin");

        mvc.perform(post("/iam.user/resetPassword/{id}", UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("iam.user.password-admin-current-user"))
                .andExpect(jsonPath("$.message")
                        .value("不能由管理员重置当前登录用户的密码，请使用修改本人密码"))
                .andExpect(jsonPath("$.actionMessage.code").value("iam.user.password-admin-current-user"))
                .andExpect(jsonPath("$.actionMessage.text")
                        .value("不能由管理员重置当前登录用户的密码，请使用修改本人密码"))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"));

        verify(userAccountDao, never()).updateById(any(UserAccount.class));
    }

    @Test
    void shouldRejectChangingCurrentUserPasswordThroughAdministrationEndpoint() throws Exception {
        currentUser = CurrentUser.systemUser(UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID, "admin");

        mvc.perform(post("/iam.user/changePassword/{id}", UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID)
                        .contentType("application/json")
                        .content("""
                                {"password":"new-secret"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("iam.user.password-admin-current-user"))
                .andExpect(jsonPath("$.message")
                        .value("不能由管理员重置当前登录用户的密码，请使用修改本人密码"))
                .andExpect(jsonPath("$.actionMessage.code").value("iam.user.password-admin-current-user"))
                .andExpect(jsonPath("$.actionMessage.text")
                        .value("不能由管理员重置当前登录用户的密码，请使用修改本人密码"))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"));

        verify(userAccountDao, never()).updateById(any(UserAccount.class));
    }

    @Test
    void shouldRejectOrganizationAccessWhenTenantIsInactive() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        doThrow(new PlatformException("Tenant is not active: tenant_a"))
                .when(tenantDao).query(any(Criteria.class), any(PageRequest.class));

        mvc.perform(get("/iam.organization/tree"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("Tenant is not active: tenant_a"));
    }

    @Test
    void shouldRequireCurrentUserTenantForOrganizationAccess() throws Exception {
        mvc.perform(get("/iam.organization/tree"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("iam.organization requires tenant context"));
    }

    @Test
    void shouldDisableTenantThroughSystemContext() throws Exception {
        Tenant existing = tenant("tenant_a", "Tenant A");
        existing.setVersion(2);
        when(tenantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(existing));
        when(tenantDao.updateByIdAndVersion(any(Tenant.class), any())).thenAnswer(invocation -> {
            assertThat(TenantContext.isSystem()).isTrue();
            return 1;
        });

        mvc.perform(post("/iam.tenant/disable/{tenantAlias}", "tenant_a")
                        .contentType("application/json")
                        .content("{\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        verify(tenantDao).updateByIdAndVersion(any(Tenant.class), any());
    }

    @Test
    void shouldExposeRoleGrantAndActionGrantEndpoints() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        AccountRoleGrant accountGrant = accountRoleGrant("grant-1", "role-1", "user-2",
                ManagementScopeType.TENANT, "tenant_a");
        when(roleService.resolveAccountRoleBindingScope("role-1", "tenant_a"))
                .thenReturn(new RoleService.AccountRoleBindingScope("tenant_a", ManagementScopeType.TENANT, "tenant_a"));
        EmploymentRoleGrant employmentGrant = employmentRoleGrant("grant-2", "role-2", "position-1");
        when(roleService.grantAccountRoleResult("role-1", "user-2", "tenant_a"))
                .thenReturn(new RoleService.RoleGrantMutationResult("grant-1", true));
        when(roleService.accountRoleGrants("role-1", "tenant_a")).thenReturn(List.of(accountGrant));
        when(roleService.deleteAccountRoleGrant("role-1", "grant-1", "tenant_a")).thenReturn(1);
        when(roleService.grantEmploymentRoleResult("role-2", "position-1"))
                .thenReturn(new RoleService.RoleGrantMutationResult("grant-2", true));
        when(roleService.employmentRoleGrants("role-2")).thenReturn(List.of(employmentGrant));
        when(roleService.deleteEmploymentRoleGrant("role-2", "grant-2")).thenReturn(1);
        when(roleService.grantAction("role-1", "sales.contract", "query",
                DataScopePolicy.OWNER, TenantScopePolicy.CURRENT_TENANT,
                null, null, null)).thenReturn(1);
        when(roleService.revokeAction("role-1", "sales.contract", "query")).thenReturn(1);

        mvc.perform(post("/iam.role/{roleId}/account-grants", "role-1")
                        .contentType("application/json")
                        .content("""
                                {"userId":"user-2","targetTenantId":"tenant_a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("grant-1"));
        mvc.perform(get("/iam.role/{roleId}/account-grants", "role-1")
                        .param("targetTenantId", "tenant_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("grant-1"))
                .andExpect(jsonPath("$[0].userId").value("user-2"))
                .andExpect(jsonPath("$[0].managementScopeType").value("tenant"));
        mvc.perform(post("/iam.role/{roleId}/account-grants/{grantId}/delete", "role-1", "grant-1")
                        .param("targetTenantId", "tenant_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
        mvc.perform(post("/iam.role/{roleId}/employment-grants", "role-2")
                        .contentType("application/json")
                        .content("""
                                {"employeePositionId":"position-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("grant-2"));
        mvc.perform(get("/iam.role/{roleId}/employment-grants", "role-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("grant-2"))
                .andExpect(jsonPath("$[0].employeePositionId").value("position-1"));
        mvc.perform(post("/iam.role/{roleId}/employment-grants/{grantId}/delete", "role-2", "grant-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
        mvc.perform(post("/iam.role/grant/{roleId}", "role-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "moduleAlias":"sales.contract",
                                  "actionCode":"query",
                                  "dataScopePolicy":"owner",
                                  "tenantScopePolicy":"currentTenant"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
        mvc.perform(post("/iam.role/revoke/{roleId}", "role-1")
                        .contentType("application/json")
                        .content("""
                                {"moduleAlias":"sales.contract","actionCode":"query"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
    }

    @Test
    void shouldQueryAccountRoleCandidatesThroughTheRoleRecordScope() {
        RoleWebController controller = new RoleWebController(grantableActionResolver);
        RoleAccountCandidateQueryService candidateQueryService = mock(RoleAccountCandidateQueryService.class);
        ReflectionTestUtils.setField(controller, "service", roleService);
        controller.setRoleAccountCandidateQueryService(candidateQueryService);
        Role role = tenantScopedRole("role-1", "tenant_a");
        UserSelectorItem user = new UserSelectorItem(
                "user-1", "alice", null, null, null, null, null, null, null);
        when(roleService.select("role-1")).thenReturn(role);
        when(roleService.resolveAccountRoleBindingScope("role-1", "tenant_a"))
                .thenReturn(new RoleService.AccountRoleBindingScope("tenant_a", ManagementScopeType.TENANT, "tenant_a"));
        when(candidateQueryService.query("alice", WebPageRequest.DEFAULT))
                .thenReturn(new WebPageResponse<>(List.of(user), 1, 0, 20, 1, true, null));

        WebPageResponse<UserSelectorItem> response = controller.accountRoleCandidates(
                "role-1", new RoleWebController.AccountRoleCandidateRequest("tenant_a", "alice", null));

        assertThat(response.records()).containsExactly(user);
        verify(roleService).resolveAccountRoleBindingScope("role-1", "tenant_a");
        verify(candidateQueryService).query("alice", WebPageRequest.DEFAULT);
    }

    @Test
    void shouldReturnActionMessageWhenRoleGrantBusinessRuleFails() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(roleService.resolveAccountRoleBindingScope("role-1", "tenant_a"))
                .thenReturn(new RoleService.AccountRoleBindingScope("tenant_a", ManagementScopeType.TENANT, "tenant_a"));
        when(roleService.grantAccountRoleResult("role-1", "user-2", "tenant_a"))
                .thenThrow(new BusinessException("iam.role.not-account-role",
                        "role is not account role: role-1"));

        mvc.perform(post("/iam.role/{roleId}/account-grants", "role-1")
                        .contentType("application/json")
                        .content("""
                                {"userId":"user-2","targetTenantId":"tenant_a"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("iam.role.not-account-role"))
                .andExpect(jsonPath("$.message").value("role is not account role: role-1"))
                .andExpect(jsonPath("$.actionMessage.code").value("iam.role.not-account-role"))
                .andExpect(jsonPath("$.actionMessage.text").value("role is not account role: role-1"))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"));
    }

    @Test
    void shouldExposeRoleBatchGrantAndRevokeEndpoints() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(roleService.grantActions(any(), any())).thenReturn(2);
        when(roleService.revokeActions(any(), any())).thenReturn(1);

        mvc.perform(post("/iam.role/grant/{roleId}/batch", "role-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "actions":[
                                    {"moduleAlias":"sales.contract","actionCode":"query"},
                                    {"moduleAlias":"sales.order","actionCode":"menu"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(2));

        mvc.perform(post("/iam.role/revoke/{roleId}/batch", "role-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "actions":[
                                    {"moduleAlias":"sales.contract","actionCode":"query"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        verify(roleService).grantActions(any(), any());
        verify(roleService).revokeActions(any(), any());
    }

    @Test
    void shouldReplaceRolePermissionMatrixAtomically() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(roleService.replacePermissionActions(any(), any())).thenReturn(2);

        mvc.perform(post("/iam.role/permissionMatrix/{roleId}/replace", "role-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "actions":[
                                    {"moduleAlias":"sales.contract","actionCode":"query","granted":true,
                                     "dataScopePolicy":"owner"},
                                    {"moduleAlias":"sales.contract","actionCode":"export","granted":false}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(2));

        ArgumentCaptor<List<RoleService.PermissionActionCommand>> actionsCaptor = ArgumentCaptor.captor();
        verify(roleService).replacePermissionActions(any(), actionsCaptor.capture());
        assertThat(actionsCaptor.getValue()).hasSize(2);
        assertThat(actionsCaptor.getValue().get(0).granted()).isTrue();
        assertThat(actionsCaptor.getValue().get(0).dataScopePolicy()).isEqualTo(DataScopePolicy.OWNER);
        assertThat(actionsCaptor.getValue().get(1).granted()).isFalse();
    }

    @Test
    void shouldExposeRolePermissionMatrixFromModuleAliases() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        List<GrantableAction> grantableActions = List.of(
                new GrantableAction("sales.contract", "query", "view", "Query", true, true));
        when(grantableActionResolver.resolve(List.of("sales.contract"))).thenReturn(grantableActions);
        when(roleService.permissionMatrix("role-1", grantableActions)).thenReturn(new RolePermissionMatrix(
                "role-1",
                List.of(new RolePermissionMatrix.Module(
                        "sales.contract",
                        List.of(new net.ximatai.muyun.spring.iam.role.RolePermissionAction(
                                "sales.contract", "query", "view", "Query",
                                true, true, true, DataScopePolicy.OWNER,
                                TenantScopePolicy.CURRENT_TENANT, null, null, null))
                ))
        ));

        mvc.perform(post("/iam.role/permissionMatrix/{roleId}", "role-1")
                        .contentType("application/json")
                        .content("""
                                {"moduleAliases":["sales.contract"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleId").value("role-1"))
                .andExpect(jsonPath("$.modules[0].moduleAlias").value("sales.contract"))
                .andExpect(jsonPath("$.modules[0].actions[0].actionCode").value("query"))
                .andExpect(jsonPath("$.modules[0].actions[0].permissionActionCode").value("view"))
                .andExpect(jsonPath("$.modules[0].actions[0].granted").value(true));
    }

    @Test
    void shouldLimitTenantRoleAuthorizationCatalogToEnabledApplications() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        RoleWebController controller = new RoleWebController(grantableActionResolver,
                provider((MenuService) null), provider(moduleService));
        ReflectionTestUtils.setField(controller, "service", roleService);
        ReflectionTestUtils.setField(controller, "tenantApplicationService", tenantApplicationService);
        Role tenantRole = new Role();
        tenantRole.setId("role-1");
        tenantRole.setTenantId("tenant-a");
        when(roleService.select("role-1")).thenReturn(tenantRole);

        PlatformModule iamOrganization = new PlatformModule();
        iamOrganization.setId("iam.organization");
        iamOrganization.setApplicationAlias("iam");
        iamOrganization.setTitle("组织管理");
        PlatformModule crmCustomer = new PlatformModule();
        crmCustomer.setId("crm.customer");
        crmCustomer.setApplicationAlias("crm");
        crmCustomer.setTitle("客户管理");
        PlatformModule salesOrder = new PlatformModule();
        salesOrder.setId("sales.order");
        salesOrder.setApplicationAlias("sales");
        salesOrder.setTitle("订单管理");
        when(moduleService.listVisibleModules()).thenReturn(List.of(iamOrganization, crmCustomer, salesOrder));
        when(tenantApplicationService.availableApplicationAliases("tenant-a")).thenReturn(List.of("iam", "sales"));

        assertThat(controller.authorizationModules("role-1").records())
                .extracting(RoleWebController.RoleAuthorizationModule::moduleAlias)
                .containsExactly("iam.organization", "sales.order");
    }

    @Test
    void shouldExposeBackendOwnedDataScopePolicyCatalog() throws Exception {
        currentUser = CurrentUser.tenantUser("user-1", "User", "tenant_a");
        when(roleService.dataScopePolicyCatalog("role-1", "sales.score"))
                .thenReturn(new RoleDataScopePolicyCatalog("role-1",
                        List.of(new RoleDataScopePolicyCatalog.Option(DataScopePolicy.INHERIT_DATA_GRANT, "继承数据授权"),
                                new RoleDataScopePolicyCatalog.Option(DataScopePolicy.REFERENCE_DEPENDENCY,
                                        "引用依赖")),
                        List.of(new RoleDataScopePolicyCatalog.ReferenceDependency(
                                "score.studentId", "学生", "school.student", "学生", "view", "查看"))));

        mvc.perform(get("/iam.role/dataScopePolicyCatalog/{roleId}", "role-1")
                        .queryParam("moduleAlias", "sales.score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].code").value("inheritDataGrant"))
                .andExpect(jsonPath("$.referenceDependencies[0].referenceFieldId").value("score.studentId"))
                .andExpect(jsonPath("$.referenceDependencies[0].referenceActionCode").value("view"));
    }

    @Test
    void shouldExposeRoleMenuMatrixFromMenuTree() throws Exception {
        MenuService menuService = mock(MenuService.class);
        RoleWebController controller = new RoleWebController(grantableActionResolver, provider(menuService));
        ReflectionTestUtils.setField(controller, "service", roleService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CurrentUserWebFilter(() ->
                        java.util.Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();

        Menu group = menu("group-1", "scheme-1", null);
        Menu contract = menu("menu-1", "scheme-1", "sales.contract");
        Menu organization = menu("menu-2", "scheme-1", "iam.organization");
        organization.setRoute("/iam/organizations");
        Menu docs = menu("menu-3", "scheme-1", "platform.docs");
        docs.setExternalUrl("https://example.com/docs");
        when(menuService.rootMenus("scheme-1")).thenReturn(List.of(group));
        when(menuService.children("scheme-1", "group-1")).thenReturn(List.of(contract, organization, docs));
        when(menuService.children("scheme-1", "menu-1")).thenReturn(List.of());
        when(menuService.children("scheme-1", "menu-2")).thenReturn(List.of());
        when(menuService.children("scheme-1", "menu-3")).thenReturn(List.of());
        when(roleService.permissionMatrix(any(), any())).thenReturn(new RolePermissionMatrix(
                "role-1",
                List.of(
                        new RolePermissionMatrix.Module(
                                "sales.contract",
                                List.of(new net.ximatai.muyun.spring.iam.role.RolePermissionAction(
                                        "sales.contract", "menu", "menu", "Menu",
                                        true, false, true, DataScopePolicy.NONE,
                                        TenantScopePolicy.CURRENT_TENANT, null, null, null))
                        ),
                        new RolePermissionMatrix.Module(
                                "iam.organization",
                                List.of(new net.ximatai.muyun.spring.iam.role.RolePermissionAction(
                                        "iam.organization", "menu", "menu", "Menu",
                                        true, false, true, DataScopePolicy.NONE,
                                        TenantScopePolicy.CURRENT_TENANT, null, null, null))
                        ),
                        new RolePermissionMatrix.Module(
                                "platform.docs",
                                List.of(new net.ximatai.muyun.spring.iam.role.RolePermissionAction(
                                        "platform.docs", "menu", "menu", "Menu",
                                        true, false, true, DataScopePolicy.NONE,
                                        TenantScopePolicy.CURRENT_TENANT, null, null, null))
                        )
                ))
        );

        mvc.perform(get("/iam.role/menuMatrix/{roleId}/{schemeId}", "role-1", "scheme-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].menu.id").value("group-1"))
                .andExpect(jsonPath("$.records[0].children[0].menu.id").value("menu-1"))
                .andExpect(jsonPath("$.records[0].children[0].granted").value(true))
                .andExpect(jsonPath("$.records[0].children[1].menu.id").value("menu-2"))
                .andExpect(jsonPath("$.records[0].children[1].granted").value(true))
                .andExpect(jsonPath("$.records[0].children[2].menu.id").value("menu-3"))
                .andExpect(jsonPath("$.records[0].children[2].granted").value(true));

        ArgumentCaptor<List<GrantableAction>> actionsCaptor = ArgumentCaptor.captor();
        verify(roleService).permissionMatrix(any(), actionsCaptor.capture());
        assertThat(actionsCaptor.getValue())
                .extracting(GrantableAction::moduleAlias)
                .containsExactly("sales.contract", "iam.organization", "platform.docs");
    }

    @Test
    void shouldCreateDepartmentInOrganizationTenantForSystemUser() throws Exception {
        DepartmentService departmentService = mock(DepartmentService.class);
        OrganizationService organizationService = mock(OrganizationService.class);
        DepartmentWebController controller = new DepartmentWebController();
        ReflectionTestUtils.setField(controller, "service", departmentService);
        controller.setOrganizationService(organizationService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new CurrentUserWebFilter(() ->
                        java.util.Optional.of(CurrentUser.systemUser("admin", "Admin"))))
                .build();
        Organization organization = organization("org-1", "HQ", "Headquarters");
        organization.setTenantId("tenant_a");
        organization.setEnabled(true);
        Department saved = department("dept-1", "org-1", "FIN", "Finance");
        saved.setTenantId("tenant_a");

        when(organizationService.requireEnabled(any(), any())).thenReturn(organization);
        when(departmentService.insert(any(Department.class))).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("tenant_a");
            Department department = invocation.getArgument(0);
            assertThat(department.getOrganizationId()).isEqualTo("org-1");
            return "dept-1";
        });
        when(departmentService.select("dept-1")).thenReturn(saved);

        mvc.perform(post("/iam.department/insert")
                        .header("X-MuYun-Page-Context", "{\"organization\":\"org-1\"}")
                        .contentType("application/json")
                        .content("""
                                {"organizationId":"org-1","code":"FIN","title":"Finance","enabled":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("dept-1"))
                .andExpect(jsonPath("$.tenantId").value("tenant_a"))
                .andExpect(jsonPath("$.organizationId").value("org-1"));
    }

    @Test
    void shouldCreateEmployeeInOrganizationTenantForSystemUser() throws Exception {
        EmployeeService employeeService = mock(EmployeeService.class);
        OrganizationService organizationService = mock(OrganizationService.class);
        EmployeeWebController controller = new EmployeeWebController(
                mock(EmployeeAccountService.class), mock(EmployeeDelegationService.class));
        ReflectionTestUtils.setField(controller, "service", employeeService);
        controller.setOrganizationService(organizationService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new CurrentUserWebFilter(() ->
                        java.util.Optional.of(CurrentUser.systemUser("admin", "Admin"))))
                .build();
        Organization organization = organization("org-1", "HQ", "Headquarters");
        organization.setTenantId("tenant_a");
        organization.setEnabled(true);
        Employee saved = employee("employee-1", "org-1", "dept-1", "E001", "Alice");
        saved.setTenantId("tenant_a");

        when(organizationService.requireEnabled(any(), any())).thenReturn(organization);
        when(employeeService.insert(any(Employee.class))).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("tenant_a");
            Employee employee = invocation.getArgument(0);
            assertThat(employee.getOrganizationId()).isEqualTo("org-1");
            assertThat(employee.getDepartmentId()).isEqualTo("dept-1");
            return "employee-1";
        });
        when(employeeService.select("employee-1")).thenReturn(saved);

        mvc.perform(post("/iam.employee/insert")
                        .header("X-MuYun-Page-Context", "{\"organization\":\"org-1\"}")
                        .contentType("application/json")
                        .content("""
                                {"organizationId":"org-1","departmentId":"dept-1","employeeNo":"E001","title":"Alice","enabled":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("employee-1"))
                .andExpect(jsonPath("$.tenantId").value("tenant_a"))
                .andExpect(jsonPath("$.organizationId").value("org-1"));
    }

    @Test
    void shouldReadEmployeeChildActionsInEmployeeTenantForSystemUser() throws Exception {
        EmployeeService employeeService = mock(EmployeeService.class);
        EmployeeAccountService employeeAccountService = mock(EmployeeAccountService.class);
        EmployeeWebController controller = new EmployeeWebController(
                employeeAccountService, mock(EmployeeDelegationService.class));
        ReflectionTestUtils.setField(controller, "service", employeeService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new CurrentUserWebFilter(() ->
                        java.util.Optional.of(CurrentUser.systemUser("admin", "Admin"))))
                .build();
        Employee existing = employee("employee-1", "org-1", "dept-1", "E001", "Alice");
        existing.setTenantId("tenant_a");

        when(employeeService.select("employee-1")).thenReturn(existing);
        when(employeeAccountService.accountOfEmployee("employee-1")).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("tenant_a");
            return null;
        });

        mvc.perform(get("/iam.employee/employee-1/account"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldExposeUserSelectorQuery() throws Exception {
        RoleService roleService = mock(RoleService.class);
        RecordingUserAccountService userAccountService = new RecordingUserAccountService();
        StaticRecordReadProjectionService projectionService = mock(StaticRecordReadProjectionService.class);
        UserAccountWebController controller = new UserAccountWebController(provider(roleService));
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        controller.setStaticRecordReadProjectionService(projectionService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CurrentUserWebFilter(() ->
                        java.util.Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        when(roleService.userIds("role-1")).thenReturn(List.of("user-2"));
        when(projectionService.queryExplicitList(
                any(),
                any(),
                any(),
                any(Criteria.class),
                any(PageRequest.class),
                any(),
                any(Sort[].class)
        )).thenReturn(java.util.Optional.of(WebPageResponse.from(PageResult.of(List.of(Map.of(
                "id", "user-2",
                "username", "alice"
        )), 1, PageRequest.of(1, 20)))));

        mvc.perform(post("/iam.user/selector/query")
                        .contentType("application/json")
                        .content("""
                                {
                                  "roleId":"role-1",
                                  "keyword":"ali"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("user-2"))
                .andExpect(jsonPath("$.records[0].username").value("alice"))
                .andExpect(jsonPath("$.records[0].organizationId").doesNotExist());

        verify(roleService).userIds("role-1");
        assertThat(userAccountService.scopedPolicies)
                .extracting(ActionExecutionPolicy::actionCode)
                .containsExactly("userSelector");
        assertThat(userAccountService.scopedPolicies.getFirst().requiresDataScope()).isTrue();
        ArgumentCaptor<Criteria> criteriaCaptor = ArgumentCaptor.captor();
        verify(projectionService).queryExplicitList(
                any(),
                any(),
                any(),
                criteriaCaptor.capture(),
                any(PageRequest.class),
                any(),
                any(Sort[].class)
        );
        assertThat(compiledCriteria(criteriaCaptor.getValue()))
                .contains("authUserId")
                .contains("enabled")
                .contains("id")
                .contains("username");
        assertThat(containsCondition(userAccountService.baseCriteria, "enabled", Boolean.TRUE)).isTrue();
    }

    @Test
    void shouldExposeEmployeeFieldsInUserSelectorWithProjectionQuery() throws Exception {
        RoleService roleService = mock(RoleService.class);
        EmployeeAccountService employeeAccountService = mock(EmployeeAccountService.class);
        EmployeeService employeeService = mock(EmployeeService.class);
        RecordingUserAccountService userAccountService = new RecordingUserAccountService();
        StaticRecordReadProjectionService projectionService = mock(StaticRecordReadProjectionService.class);
        UserAccountWebController controller = new UserAccountWebController(
                provider(roleService),
                provider(employeeAccountService),
                provider(employeeService),
                null
        );
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        controller.setStaticRecordReadProjectionService(projectionService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CurrentUserWebFilter(() ->
                        java.util.Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        when(roleService.userIds("role-1")).thenReturn(List.of("user-2"));
        when(projectionService.queryExplicitList(
                any(),
                any(),
                any(),
                any(Criteria.class),
                any(PageRequest.class),
                any(),
                any(Sort[].class)
        )).thenReturn(java.util.Optional.of(WebPageResponse.from(PageResult.of(List.of(Map.of(
                "id", "user-2",
                "username", "alice",
                "employeeId", "employee-1",
                "employeeNo", "E001",
                "employeeTitle", "Alice Zhang",
                "employeeOrganizationId", "org-1",
                "organizationTitle", "研发中心",
                "employeeDepartmentId", "dept-1",
                "departmentTitle", "平台部"
        )), 1, PageRequest.of(1, 20)))));

        mvc.perform(post("/iam.user/selector/query")
                        .contentType("application/json")
                        .content("""
                                {
                                  "roleId":"role-1",
                                  "keyword":"ali"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("user-2"))
                .andExpect(jsonPath("$.records[0].employeeId").value("employee-1"))
                .andExpect(jsonPath("$.records[0].employeeNo").value("E001"))
                .andExpect(jsonPath("$.records[0].employeeTitle").value("Alice Zhang"))
                .andExpect(jsonPath("$.records[0].organizationId").value("org-1"))
                .andExpect(jsonPath("$.records[0].organizationTitle").value("研发中心"))
                .andExpect(jsonPath("$.records[0].departmentId").value("dept-1"))
                .andExpect(jsonPath("$.records[0].departmentTitle").value("平台部"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> fieldsCaptor = ArgumentCaptor.captor();
        verify(projectionService).queryExplicitList(
                any(),
                any(),
                fieldsCaptor.capture(),
                any(Criteria.class),
                any(PageRequest.class),
                any(),
                any(Sort[].class)
        );
        assertThat(fieldsCaptor.getValue()).containsExactly(
                "id",
                "username",
                "employeeId",
                "employeeNo",
                "employeeTitle",
                "employeeOrganizationId",
                "organizationTitle",
                "employeeDepartmentId",
                "departmentTitle"
        );
        verify(employeeAccountService, never()).accountsOfUsers(any());
        verify(employeeAccountService, never()).accountOfUser(any());
        verify(employeeService, never()).select(any());
    }

    @Test
    void shouldFailUserSelectorWhenProjectionServiceIsUnavailable() throws Exception {
        RecordingUserAccountService userAccountService = new RecordingUserAccountService();
        UserAccountWebController controller = new UserAccountWebController();
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new CurrentUserWebFilter(() ->
                        java.util.Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();

        mvc.perform(post("/iam.user/selector/query")
                        .contentType("application/json")
                        .content("""
                                {
                                  "keyword":"ali"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("系统暂时不可用，请稍后重试"));
    }

    private Tenant tenant(String alias, String title) {
        Tenant tenant = new Tenant();
        tenant.setAlias(alias);
        tenant.setTitle(title);
        tenant.setEnabled(Boolean.TRUE);
        tenant.setSortOrder(1);
        return tenant;
    }

    private Organization organization(String id, String code, String title) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setCode(code);
        organization.setTitle(title);
        organization.setEnabled(Boolean.TRUE);
        organization.setSortOrder(1);
        return organization;
    }

    private Department department(String id, String organizationId, String code, String title) {
        Department department = new Department();
        department.setId(id);
        department.setOrganizationId(organizationId);
        department.setCode(code);
        department.setTitle(title);
        department.setEnabled(Boolean.TRUE);
        department.setSortOrder(1);
        return department;
    }

    private Employee employee(String id, String organizationId, String departmentId, String employeeNo, String title) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setOrganizationId(organizationId);
        employee.setDepartmentId(departmentId);
        employee.setEmployeeNo(employeeNo);
        employee.setTitle(title);
        employee.setEnabled(Boolean.TRUE);
        employee.setSortOrder(1);
        return employee;
    }

    private UserAccount user(String id, String username, String title) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUsername(username);
        user.setTitle(title);
        user.setEnabled(Boolean.TRUE);
        user.setSortOrder(1);
        return user;
    }

    private Position position(String id, String categoryId, String code, String title) {
        Position position = new Position();
        position.setId(id);
        position.setCategoryId(categoryId);
        position.setCode(code);
        position.setTitle(title);
        position.setEnabled(Boolean.TRUE);
        position.setSortOrder(1);
        return position;
    }

    private Role tenantScopedRole(String id, String tenantId) {
        Role role = new Role();
        role.setId(id);
        role.setTenantId(tenantId);
        role.setTitle("Tenant Role");
        role.setOwnerScopeType(RoleOwnerScopeType.TENANT);
        role.setOwnerScopeId(tenantId);
        role.setOwnerScopeKey("tenant:" + tenantId);
        role.setEnabled(Boolean.TRUE);
        role.setSortOrder(1);
        return role;
    }

    private AccountRoleGrant accountRoleGrant(String id,
                                              String roleId,
                                              String userId,
                                              ManagementScopeType scopeType,
                                              String scopeId) {
        AccountRoleGrant grant = new AccountRoleGrant();
        grant.setId(id);
        grant.setRoleId(roleId);
        grant.setUserId(userId);
        grant.setManagementScopeType(scopeType);
        grant.setManagementScopeId(scopeId);
        grant.setEnabled(Boolean.TRUE);
        return grant;
    }

    private EmploymentRoleGrant employmentRoleGrant(String id, String roleId, String employeePositionId) {
        EmploymentRoleGrant grant = new EmploymentRoleGrant();
        grant.setId(id);
        grant.setRoleId(roleId);
        grant.setEmployeePositionId(employeePositionId);
        grant.setEnabled(Boolean.TRUE);
        return grant;
    }

    private Menu menu(String id, String schemeId, String moduleAlias) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setSchemeId(schemeId);
        if (moduleAlias != null && !moduleAlias.isBlank()) {
            menu.setOpenMode(MenuOpenMode.TAB);
        }
        menu.setModuleAlias(moduleAlias);
        menu.setTitle(id);
        menu.setEnabled(Boolean.TRUE);
        return menu;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private boolean containsCondition(Criteria criteria, String fieldName, Object value) {
        return criteria.getClauses().stream()
                .anyMatch(clause -> fieldName.equals(clause.getField())
                        && clause.getValues().contains(value));
    }

    private StandardModuleWebRuntime positionRuntime(PositionWebController controller) {
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", PositionService.MODULE_ALIAS, "岗位管理")
                .entry(ModuleEntryType.ROUTE, "/iam.position", null)
                .capabilities(java.util.Set.of(net.ximatai.muyun.spring.common.platform.EntityCapability.CRUD,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.ENABLE,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.SORT))
                .entities(List.of(new StaticEntityDefinitionCompiler().compile("position", PositionService.MODULE_ALIAS,
                        Position.class)))
                .modelClass(Position.class)
                .uiDefinition(controller.moduleUiDefinition())
                .build();
        PositionCategoryWebController categoryController = new PositionCategoryWebController();
        StaticModuleDefinition categoryDefinition = StaticModuleDefinition.builder("iam", PositionCategoryService.MODULE_ALIAS,
                        "岗位分类")
                .entry(ModuleEntryType.ROUTE, "/iam.position-category", null)
                .capabilities(java.util.Set.of(net.ximatai.muyun.spring.common.platform.EntityCapability.CRUD,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.TREE,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.ENABLE,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.SORT,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.RECYCLE_BIN))

                .actions(java.util.Arrays.stream(net.ximatai.muyun.spring.common.platform.PlatformAction.values())
                        .map(net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition::platformAction).toList())
                .entities(List.of(new StaticEntityDefinitionCompiler().compile("position_category",
                        PositionCategoryService.MODULE_ALIAS, PositionCategory.class)))
                .modelClass(PositionCategory.class)
                .uiDefinition(categoryController.moduleUiDefinition())
                .build();
        StaticModuleDefinitionCatalog catalog = new StaticModuleDefinitionCatalog(List.of(definition, categoryDefinition));
        return new StandardModuleWebRuntime(new ModuleExecutionPlanCatalog(catalog),
                new StaticRecordReadProjectionService(catalog));
    }

    private StandardModuleWebRuntime tenantRuntime(TenantWebController controller) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("tenantController", TenantWebController.class, () -> controller);
            context.refresh();
            StaticModuleDefinitionCatalog catalog = new StaticModuleDefinitionCatalog(
                    new net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionScanner(context).scan());
            return new StandardModuleWebRuntime(new ModuleExecutionPlanCatalog(catalog),
                    new StaticRecordReadProjectionService(catalog));
        }
    }

    private StandardModuleWebRuntime userRuntime(UserAccountWebController controller) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("userController", UserAccountWebController.class, () -> controller);
            context.refresh();
            StaticModuleDefinitionCatalog catalog = new StaticModuleDefinitionCatalog(
                    new net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionScanner(context).scan());
            return new StandardModuleWebRuntime(new ModuleExecutionPlanCatalog(catalog,
                    new ListQuerySummaryContributorCatalog(List.of(new UserOnlineQuerySummaryContributor(
                            mock(net.ximatai.muyun.spring.iam.user.UserSessionPresenceService.class))))),
                    new StaticRecordReadProjectionService(catalog));
        }
    }

    private static final class PlanOnlyPositionWebController extends PositionWebController {
        private boolean rejectDefinitionLookup;

        private void rejectDefinitionLookup() {
            rejectDefinitionLookup = true;
        }

        @Override
        public net.ximatai.muyun.spring.platform.web.ModuleUiDefinition moduleUiDefinition() {
            if (rejectDefinitionLookup) {
                throw new AssertionError("request runtime must not call moduleUiDefinition");
            }
            return super.moduleUiDefinition();
        }
    }

    private String compiledCriteria(Criteria criteria) {
        return new CriteriaSqlCompiler()
                .compile(criteria, field -> field, DBInfo.Type.POSTGRESQL)
                .getSql();
    }

    private static final class RecordingUserAccountService extends UserAccountService {
        private final List<ActionExecutionPolicy> scopedPolicies = new java.util.ArrayList<>();
        private Criteria baseCriteria;
        private Criteria scopedCriteria;
        private Criteria queriedCriteria;
        private PageResult<UserAccount> result = PageResult.of(List.of(), 0, PageRequest.of(1, 20));

        private RecordingUserAccountService() {
            super(
                    mock(UserAccountDao.class),
                    mock(ActiveTenantVerifier.class),
                    new PasswordHashingService(),
                    new net.ximatai.muyun.spring.iam.user.UserAccountAuthorizationServices(
                            net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService::new,
                            mock(net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao.class)),
                    new net.ximatai.muyun.spring.iam.user.UserAccountSecurityServices(
                            java.util.Optional.empty(),
                            net.ximatai.muyun.spring.iam.user.UserSecurityEventPublisher.NOOP,
                            mock(net.ximatai.muyun.spring.iam.user.UserSessionRevocationService.class),
                            mock(net.ximatai.muyun.spring.iam.user.UserSessionPresenceService.class)));
        }

        @Override
        public DataScopeCriteriaResult readScopeByPolicy(ActionExecutionPolicy policy, Criteria criteria) {
            scopedPolicies.add(policy);
            baseCriteria = criteria;
            scopedCriteria = Criteria.of().eq("authUserId", "user-1");
            scopedCriteria.andGroup(criteria.getRoot());
            return DataScopeCriteriaResult.restricted(scopedCriteria);
        }

        @Override
        public PageResult<UserAccount> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            queriedCriteria = criteria;
            return result;
        }
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private MockMvc securedRoleMvc(TenantService scopedTenantService,
                                   OrganizationService scopedOrganizationService,
                                   HandlerInterceptor... additionalInterceptors) {
        RoleWebController controller = new RoleWebController(grantableActionResolver);
        ReflectionTestUtils.setField(controller, "service", roleService);
        controller.setRoleScopeSelectionResolver(new RoleScopePageSelectionResolver(
                scopedTenantService, scopedOrganizationService));
        var builder = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ActionEndpointInterceptor(new AllowAllActionExecutionPolicyService(),
                        new ActionEndpointContextResolver()))
                .addFilters(new CurrentUserWebFilter(() -> Optional.ofNullable(currentUser)))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper));
        if (additionalInterceptors != null && additionalInterceptors.length > 0) {
            builder.addInterceptors(additionalInterceptors);
        }
        return builder.build();
    }

    private static String roleScopeHeader(String key) {
        return "{\"kind\":\"roleScope\",\"key\":\"" + key + "\"}";
    }
}
