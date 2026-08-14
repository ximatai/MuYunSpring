package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.role.RoleGrantableActionResolver;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.form.FormAbility;
import net.ximatai.muyun.spring.ability.query.QueryCompiler;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.ability.reference.ReferencePath;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjection;
import net.ximatai.muyun.spring.ability.action.ActionMessage;
import net.ximatai.muyun.spring.ability.action.DataChangeIntent;
import net.ximatai.muyun.spring.ability.action.DataChangeOperation;
import net.ximatai.muyun.spring.ability.action.MutationContextHolder;
import net.ximatai.muyun.spring.web.MuYunSpringJacksonConfiguration;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinition;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionCatalog;
import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;
import net.ximatai.muyun.spring.platform.module.StaticReferenceCompiler;
import net.ximatai.muyun.spring.platform.web.StaticRecordReadProjectionService;
import net.ximatai.muyun.spring.platform.web.ActionEndpointWebConfiguration;
import net.ximatai.muyun.spring.web.ActionResultResponseAdvice;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserProvider;
import net.ximatai.muyun.spring.dynamic.metadata.StaticEntityDefinitionCompiler;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegation;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegationService;
import net.ximatai.muyun.spring.iam.employee.EmployeePosition;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.position.Position;
import net.ximatai.muyun.spring.iam.position.PositionCategoryService;
import net.ximatai.muyun.spring.iam.position.PositionService;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrant;
import net.ximatai.muyun.spring.iam.role.DataScopePolicy;
import net.ximatai.muyun.spring.iam.role.EmploymentRoleGrant;
import net.ximatai.muyun.spring.iam.role.GrantableAction;
import net.ximatai.muyun.spring.iam.role.ManagementScopeType;
import net.ximatai.muyun.spring.iam.role.Role;
import net.ximatai.muyun.spring.iam.role.RolePermissionAction;
import net.ximatai.muyun.spring.iam.role.RolePermissionMatrix;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.role.TenantScopePolicy;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        TenantWebController.class,
        OrganizationWebController.class,
        DepartmentWebController.class,
        EmployeeWebController.class,
        PositionWebController.class,
        RoleWebController.class
})
@Import({
        CurrentUserWebFilter.class,
        MuYunSpringJacksonConfiguration.class,
        ActionEndpointWebConfiguration.class,
        ActionResultResponseAdvice.class,
        PlatformWebExceptionHandler.class,
        StaticRecordReadProjectionService.class
})
class IamWebControllerIT {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TenantService tenantService;

    @MockitoBean
    private RecycleBinFacade recycleBinFacade;

    @MockitoBean
    private OrganizationService organizationService;

    @MockitoBean
    private DepartmentService departmentService;

    @MockitoBean
    private EmployeeService employeeService;

    @MockitoBean
    private EmployeePositionService employeePositionService;

    @MockitoBean
    private EmployeeAccountService employeeAccountService;

    @MockitoBean
    private EmployeeDelegationService employeeDelegationService;

    @MockitoBean
    private StaticModuleDefinitionCatalog staticModuleDefinitionCatalog;

    @MockitoBean
    private PositionService positionService;

    @MockitoBean
    private PositionCategoryService positionCategoryService;

    @MockitoBean
    private RoleService roleService;

    @MockitoBean
    private RoleGrantableActionResolver roleGrantableActionResolver;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    void shouldUseInjectedServiceAndCurrentUserTenantInRealMvcContext() throws Exception {
        Organization organization = new Organization();
        organization.setId("org-1");
        organization.setCode("HQ");
        organization.setTitle("Headquarters");
        organization.setParentId(TreeAbility.ROOT_ID);
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        doCallRealMethod().when(organizationService).scopedTreeCriteria(any(Criteria.class), any(String.class));
        when(organizationService.listForAction(eq(PlatformAction.TREE),
                any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(organization));

        mvc.perform(get("/iam.organization/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("org-1"))
                .andExpect(jsonPath("$.records[0].children").isArray());
    }

    @Test
    void shouldBindTreeSortEndpointInRealMvcContext() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(organizationService.requireRecordScopeResult(any(), any()))
                .thenReturn(DataScopeCriteriaResult.unrestricted(Criteria.of()));
        doAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get())
                .when(organizationService).withDataScopeTenant(any(), any());

        mvc.perform(post("/iam.organization/sort/org-1")
                        .contentType("application/json")
                        .content("""
                                {"previousId":"org-0","parentId":"root"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1))
                .andExpect(jsonPath("$.message.code").value("platform.crud.sorted"))
                .andExpect(jsonPath("$.message.text").value("排序成功"))
                .andExpect(jsonPath("$.changes[?(@.type == 'collection-changed' && @.moduleAlias == 'iam.organization')]")
                        .exists());

        verify(organizationService).moveInTree(any(Criteria.class), eq("org-1"), eq("org-0"), eq(null), eq(TreeAbility.ROOT_ID));
    }

    @Test
    void shouldBindDepartmentTreeEndpointWithOrganizationScopeInRealMvcContext() throws Exception {
        Department department = new Department();
        department.setId("dept-1");
        department.setOrganizationId("org-1");
        department.setCode("FIN");
        department.setTitle("Finance");
        department.setParentId(TreeAbility.ROOT_ID);
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        doCallRealMethod().when(departmentService).scopedTreeCriteria(any(Criteria.class), any(String.class));
        when(departmentService.listForAction(eq(PlatformAction.TREE),
                any(Criteria.class), any(PageRequest.class), any(Sort.class)))
                .thenReturn(List.of(department))
                .thenReturn(List.of());

        mvc.perform(get("/iam.department/tree").param("organizationId", "org-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("dept-1"))
                .andExpect(jsonPath("$.records[0].record.organizationId").value("org-1"))
                .andExpect(jsonPath("$.records[0].children").isArray());

        ArgumentCaptor<Criteria> criteriaCaptor = ArgumentCaptor.captor();
        verify(departmentService, atLeastOnce()).listForAction(eq(PlatformAction.TREE),
                criteriaCaptor.capture(), any(PageRequest.class), any(Sort.class));
        assertThat(criteriaCaptor.getAllValues()).anySatisfy(criteria -> {
            assertThat(containsCondition(criteria, "organizationId", "org-1")).isTrue();
            assertThat(containsCondition(criteria, "parentId", TreeAbility.ROOT_ID)).isTrue();
        });
    }

    @Test
    void shouldBindDepartmentTreeSortEndpointInRealMvcContext() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(departmentService.requireRecordScopeResult(any(), any()))
                .thenReturn(DataScopeCriteriaResult.unrestricted(Criteria.of()));
        doAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get())
                .when(departmentService).withDataScopeTenant(any(), any());

        mvc.perform(post("/iam.department/sort/dept-1")
                        .param("organizationId", "org-1")
                        .contentType("application/json")
                        .content("""
                                {"previousId":"dept-0","parentId":"root"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1))
                .andExpect(jsonPath("$.message.code").value("platform.crud.sorted"))
                .andExpect(jsonPath("$.message.text").value("排序成功"))
                .andExpect(jsonPath("$.changes[?(@.type == 'collection-changed' && @.moduleAlias == 'iam.department')]")
                        .exists());

        verify(departmentService).moveInTree(any(Criteria.class), eq("dept-1"), eq("dept-0"), eq(null), eq(TreeAbility.ROOT_ID));
    }

    @Test
    void shouldBindPlainSortEndpointInRealMvcContext() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.systemUser("admin", "Admin")));

        mvc.perform(post("/iam.tenant/sort/tenant-1")
                        .contentType("application/json")
                        .content("""
                                {"previousId":"tenant-0"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1))
                .andExpect(jsonPath("$.message.code").value("platform.crud.sorted"))
                .andExpect(jsonPath("$.message.text").value("排序成功"))
                .andExpect(jsonPath("$.changes[?(@.type == 'collection-changed' && @.moduleAlias == 'iam.tenant')]")
                        .exists());

        verify(tenantService).moveAfter("tenant-1", "tenant-0");
    }

    @Test
    void shouldDeriveStandardCreateMutationFromReturnedRecordInRealMvcContext() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId("tenant-2");
        tenant.setTitle("Tenant 2");
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.systemUser("admin", "Admin")));
        when(tenantService.insert(any(Tenant.class))).thenReturn("tenant-2");
        when(tenantService.select("tenant-2")).thenReturn(tenant);

        mvc.perform(post("/iam.tenant/insert")
                        .contentType("application/json")
                        .content("""
                                {"id":"tenant-2","title":"Tenant 2"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("tenant-2"))
                .andExpect(jsonPath("$.message.code").value("platform.crud.created"))
                .andExpect(jsonPath("$.message.text").value("「Tenant 2」新增成功"))
                .andExpect(jsonPath("$.changes[?(@.type == 'record-created' && @.moduleAlias == 'iam.tenant' && @.recordId == 'tenant-2')]")
                        .exists());
    }

    @Test
    void shouldBindEmployeeSortEndpointInRealMvcContext() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));

        mvc.perform(post("/iam.employee/sort/employee-1")
                        .contentType("application/json")
                        .content("""
                                {"previousId":"employee-0"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1))
                .andExpect(jsonPath("$.message.code").value("platform.crud.sorted"))
                .andExpect(jsonPath("$.message.text").value("排序成功"))
                .andExpect(jsonPath("$.changes[?(@.type == 'collection-changed' && @.moduleAlias == 'iam.employee')]")
                        .exists());

        verify(employeeService).moveAfter("employee-1", "employee-0");
    }

    @Test
    void shouldBindEmployeeViewEndpointInRealMvcContext() throws Exception {
        Employee employee = new Employee();
        employee.setId("employee-1");
        employee.setOrganizationId("org-1");
        employee.setDepartmentId("dept-1");
        employee.setEmployeeNo("E001");
        employee.setTitle("Alice");
        employee.setGender("1");
        employee.setGenderTitle("男");
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(employeeService.selectForAction(PlatformAction.VIEW, "employee-1")).thenReturn(employee);

        mvc.perform(get("/iam.employee/view/employee-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("employee-1"))
                .andExpect(jsonPath("$.departmentId").value("dept-1"))
                .andExpect(jsonPath("$.gender").value("1"))
                .andExpect(jsonPath("$.genderTitle").value("男"));
    }

    @Test
    void shouldExposeEmployeeQuerySchemaInTenantScope() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(staticModuleDefinitionCatalog.find(EmployeeService.MODULE_ALIAS))
                .thenReturn(Optional.of(employeeStaticModuleDefinition()));
        when(employeeService.queryDescriptor()).thenReturn(employeeQueryDescriptor());

        mvc.perform(get("/iam.employee/query/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeName").value(EmployeeService.MODULE_ALIAS))
                .andExpect(jsonPath("$.entityAlias").doesNotExist())
                .andExpect(jsonPath("$.quickSearch.enabled").value(true))
                .andExpect(jsonPath("$.quickSearch.fields[0]").value("employeeNo"))
                .andExpect(jsonPath("$.quickSearch.fieldSchemas[?(@.name == 'employeeNo')].title")
                        .value(org.hamcrest.Matchers.contains("职员编号")))
                .andExpect(jsonPath("$.fields[?(@.name == 'employeeNo')].title")
                        .value(org.hamcrest.Matchers.contains("职员编号")))
                .andExpect(jsonPath("$.fields[?(@.name == 'enabled')].valueType")
                        .value(org.hamcrest.Matchers.contains("BOOLEAN")))
                .andExpect(jsonPath("$.fields[?(@.name == 'organizationTitle')].sortable")
                        .value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.fields[?(@.name == 'organizationTitle')].operators")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.fields[?(@.name == 'username')].operators")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.fields[?(@.name == 'accountBound')].valueType")
                        .value(org.hamcrest.Matchers.contains("BOOLEAN")))
                .andExpect(jsonPath("$.externalCriteria[0].key").value("departmentScope"));

        verify(employeeService).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldExposeEmployeeFormSchemaFromStaticUiDefinition() throws Exception {
        assertThat(FormAbility.class.isAssignableFrom(EmployeeService.class)).isFalse();
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));

        mvc.perform(get("/iam.employee/form/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeName").value(EmployeeService.MODULE_ALIAS))
                .andExpect(jsonPath("$.title").value("职员档案"))
                .andExpect(jsonPath("$.fields[0].name").value("organizationId"))
                .andExpect(jsonPath("$.fields[0].title").value("所属机构"))
                .andExpect(jsonPath("$.fields[0].required").value(true))
                .andExpect(jsonPath("$.fields[0].readOnly").value(true))
                .andExpect(jsonPath("$.fields[?(@.name == 'enabled')].controlType")
                        .value(org.hamcrest.Matchers.contains("SWITCH")))
                .andExpect(jsonPath("$.fields[?(@.name == 'gender')].controlType")
                        .value(org.hamcrest.Matchers.contains("SELECT")))
                .andExpect(jsonPath("$.fields[?(@.name == 'gender')].optionBinding.sourceType")
                        .value(org.hamcrest.Matchers.contains("dictionary")))
                .andExpect(jsonPath("$.fields[?(@.name == 'gender')].optionBinding.source")
                        .value(org.hamcrest.Matchers.contains("iam.gender")))
                .andExpect(jsonPath("$.fields[?(@.name == 'gender')].optionTitleField")
                        .value(org.hamcrest.Matchers.contains("genderTitle")))
                .andExpect(jsonPath("$.fields[?(@.name == 'avatarAssetId')]").isEmpty());

    }

    @Test
    void shouldExposeDepartmentFormSchemaFromStaticUiDefinition() throws Exception {
        assertThat(FormAbility.class.isAssignableFrom(DepartmentService.class)).isFalse();
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));

        mvc.perform(get("/iam.department/form/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeName").value(DepartmentService.MODULE_ALIAS))
                .andExpect(jsonPath("$.title").value("部门档案"))
                .andExpect(jsonPath("$.fields[0].name").value("organizationId"))
                .andExpect(jsonPath("$.fields[0].title").value("所属机构"))
                .andExpect(jsonPath("$.fields[0].required").value(true))
                .andExpect(jsonPath("$.fields[0].readOnly").value(true))
                .andExpect(jsonPath("$.fields[1].name").value("parentId"))
                .andExpect(jsonPath("$.fields[1].title").value("上级部门"))
                .andExpect(jsonPath("$.fields[?(@.name == 'enabled')].controlType")
                        .value(org.hamcrest.Matchers.contains("SWITCH")));
    }

    @Test
    void shouldQueryEmployeesWithDeclaredConditionsQuickSearchAndDepartmentScope() throws Exception {
        Employee employee = new Employee();
        employee.setId("employee-1");
        employee.setOrganizationId("org-1");
        employee.setDepartmentId("dept-child");
        employee.setEmployeeNo("E001");
        employee.setTitle("Alice");
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        wireEmployeeQueryAbility();
        when(departmentService.selfAndDescendantIds("org-1", "dept-root"))
                .thenReturn(List.of("dept-root", "dept-child"));
        when(employeeService.pageQueryForAction(eq(PlatformAction.QUERY),
                any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(employee), 1, PageRequest.of(1, 20)));

        mvc.perform(post("/iam.employee/query")
                        .contentType("application/json")
                        .content("""
                                {
                                  "quickSearch": "Alice",
                                  "conditions": [
                                    {"fieldName":"enabled","operator":"EQ","values":[true]}
                                  ],
                                  "externalQueryValues": {
                                    "departmentScope": {
                                      "organizationId": "org-1",
                                      "departmentId": "dept-root",
                                      "includeChildren": true
                                    }
                                  },
                                  "sorts": [{"field":"employeeNo","desc":false}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("employee-1"));

        org.mockito.ArgumentCaptor<Criteria> criteria = org.mockito.ArgumentCaptor.forClass(Criteria.class);
        org.mockito.ArgumentCaptor<Sort[]> sorts = org.mockito.ArgumentCaptor.forClass(Sort[].class);
        verify(employeeService).pageQueryForAction(eq(PlatformAction.QUERY),
                criteria.capture(), any(PageRequest.class), sorts.capture());
        assertThat(containsCondition(criteria.getValue(), "enabled", true)).isTrue();
        assertThat(containsCondition(criteria.getValue(), "organizationId", "org-1")).isTrue();
        assertThat(containsCondition(criteria.getValue(), "departmentId", "dept-child")).isTrue();
        assertThat(containsCondition(criteria.getValue(), "title", "%Alice%")).isTrue();
        assertThat(sorts.getValue()).hasSize(1);
    }

    @Test
    void shouldProjectEmployeeQueryResponseByResolvedListView() throws Exception {
        Employee employee = new Employee();
        employee.setId("employee-1");
        employee.setTenantId("tenant_a");
        employee.setVersion(7);
        employee.setOrganizationId("org-1");
        employee.setDepartmentId("dept-child");
        employee.setEmployeeNo("E001");
        employee.setTitle("Alice");
        employee.setMobile("13800000000");
        employee.setEmail("alice@example.test");
        employee.setEnabled(Boolean.TRUE);
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        wireEmployeeQueryAbility();
        when(staticModuleDefinitionCatalog.find(EmployeeService.MODULE_ALIAS))
                .thenReturn(Optional.of(employeeStaticModuleDefinition()));
        when(employeeService.pageQueryForAction(eq(PlatformAction.QUERY),
                any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(employee), 1, PageRequest.of(1, 20)));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target ->
                ReferenceTarget.of("iam", "organization").equals(target)
                        ? java.util.Optional.of(organizationService)
                        : java.util.Optional.empty());
        try {
            mvc.perform(post("/iam.employee/query")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[0].id").value("employee-1"))
                    .andExpect(jsonPath("$.records[0].employeeNo").value("E001"))
                    .andExpect(jsonPath("$.records[0].title").value("Alice"))
                    .andExpect(jsonPath("$.records[0].mobile").value("13800000000"))
                    .andExpect(jsonPath("$.records[0].email").value("alice@example.test"))
                    .andExpect(jsonPath("$.records[0].enabled").value(true))
                    .andExpect(jsonPath("$.records[0].organizationId").doesNotExist())
                    .andExpect(jsonPath("$.records[0].departmentId").doesNotExist())
                    .andExpect(jsonPath("$.records[0].tenantId").doesNotExist())
                    .andExpect(jsonPath("$.records[0].version").value(7));
        } finally {
            PlatformAbilityRuntime.resetReferenceTargetResolver();
        }
    }

    @Test
    void shouldProjectDepartmentQueryResponseByResolvedListView() throws Exception {
        Department department = new Department();
        department.setId("dept-1");
        department.setTenantId("tenant_a");
        department.setVersion(3);
        department.setOrganizationId("org-1");
        department.setParentId(TreeAbility.ROOT_ID);
        department.setCode("FIN");
        department.setTitle("财务部");
        department.setEnabled(Boolean.TRUE);
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(staticModuleDefinitionCatalog.find(DepartmentService.MODULE_ALIAS))
                .thenReturn(Optional.of(departmentStaticModuleDefinition()));
        when(departmentService.pageQueryForAction(eq(PlatformAction.QUERY),
                any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(department), 1, PageRequest.of(1, 20)));

        mvc.perform(post("/iam.department/query")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("dept-1"))
                .andExpect(jsonPath("$.records[0].code").value("FIN"))
                .andExpect(jsonPath("$.records[0].title").value("财务部"))
                .andExpect(jsonPath("$.records[0].enabled").value(true))
                .andExpect(jsonPath("$.records[0].organizationId").doesNotExist())
                .andExpect(jsonPath("$.records[0].parentId").doesNotExist())
                .andExpect(jsonPath("$.records[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.records[0].version").value(3));
    }

    @Test
    void shouldRejectUndeclaredEmployeeQueryFieldsInRealMvcContext() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        wireEmployeeQueryAbility();

        mvc.perform(post("/iam.employee/query")
                        .contentType("application/json")
                        .content("""
                                {
                                  "conditions": [
                                    {"fieldName":"passwordHash","operator":"EQ","values":["secret"]}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message")
                        .value("query field is not supported by iam.employee: passwordHash"));

        verify(employeeService, never()).pageQuery(isA(Criteria.class), isA(PageRequest.class), any(Sort[].class));
    }

    @Test
    void shouldRejectEmployeeQueryTemplateUntilStaticTemplateSourceExists() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        wireEmployeeQueryAbility();

        mvc.perform(post("/iam.employee/query")
                        .contentType("application/json")
                        .content("""
                                {"queryTemplateId":"employee-default"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("query template is not supported by iam.employee"));

        verify(employeeService, never()).pageQuery(isA(Criteria.class), isA(PageRequest.class), any(Sort[].class));
    }

    @Test
    void shouldBindEmployeePositionEndpointsInRealMvcContext() throws Exception {
        EmployeePosition relation = new EmployeePosition();
        relation.setId("relation-1");
        relation.setEmployeeId("employee-1");
        relation.setOrganizationId("org-1");
        relation.setDepartmentId("dept-1");
        relation.setPositionId("position-1");
        relation.setPrimaryPosition(Boolean.TRUE);
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(employeePositionService.positions("employee-1")).thenReturn(List.of(relation));
        when(employeePositionService.addPosition(eq("employee-1"), any())).thenReturn("relation-1");
        when(employeePositionService.select("relation-1")).thenReturn(relation);
        when(employeePositionService.deletePosition("employee-1", "relation-1")).thenReturn(1);
        when(employeePositionService.makePrimaryPosition("employee-1", "relation-1")).thenReturn(1);

        mvc.perform(get("/iam.employee/employee-1/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("relation-1"))
                .andExpect(jsonPath("$.records[0].primaryPosition").value(true));

        mvc.perform(post("/iam.employee/employee-1/positions")
                        .contentType("application/json")
                        .content("""
                                {"organizationId":"org-1","departmentId":"dept-1","positionId":"position-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("relation-1"));

        mvc.perform(post("/iam.employee/employee-1/positions/relation-1/delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        mvc.perform(post("/iam.employee/employee-1/positions/relation-1/primary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
    }

    @Test
    void shouldBindEmployeePositionSortEndpointInRealMvcContext() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));

        mvc.perform(post("/iam.employee/employee-1/positions/relation-1/sort")
                        .contentType("application/json")
                        .content("""
                                {"previousId":"relation-0"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        verify(employeePositionService).moveEmployeePosition("employee-1", "relation-1", "relation-0", null);
    }

    @Test
    void shouldBindEmployeeAccountEndpointsInRealMvcContext() throws Exception {
        EmployeeAccount binding = employeeAccount("binding-1", "employee-1", "user-2");
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(employeeAccountService.accountOfEmployee("employee-1")).thenReturn(binding);
        when(employeeAccountService.bindAccount(eq("employee-1"), any(EmployeeAccount.class))).thenReturn("binding-1");
        when(employeeAccountService.select("binding-1")).thenReturn(binding);
        when(employeeAccountService.removeAccount("employee-1")).thenAnswer(invocation -> {
            MutationContextHolder.current().ifPresent(context -> {
                context.message(ActionMessage.success("iam.employee-account.removed", "账户已移除"));
                context.record(new DataChangeIntent(DataChangeOperation.DELETED,
                        EmployeeAccountService.class, "binding-1"));
                context.record(new DataChangeIntent(DataChangeOperation.DELETED,
                        UserAccountService.class, "user-2"));
                context.record(new DataChangeIntent(DataChangeOperation.UPDATED,
                        EmployeeService.class, "employee-1"));
            });
            return 1;
        });
        UserAccount provisioned = new UserAccount();
        provisioned.setId("user-2");
        provisioned.setUsername("alice");
        when(employeeAccountService.provisionAccount(eq("employee-1"), any(UserAccount.class)))
                .thenAnswer(invocation -> {
                    MutationContextHolder.current().ifPresent(context -> {
                        context.message(ActionMessage.success("iam.employee-account.provisioned",
                                "账号已创建并绑定职员"));
                        context.record(new DataChangeIntent(DataChangeOperation.CREATED,
                                UserAccountService.class, "user-2"));
                        context.record(new DataChangeIntent(DataChangeOperation.CREATED,
                                EmployeeAccountService.class, "binding-1"));
                        context.record(new DataChangeIntent(DataChangeOperation.UPDATED,
                                EmployeeService.class, "employee-1"));
                    });
                    return new EmployeeAccountService.AccountProvisionResult(provisioned, binding);
                });
        when(staticModuleDefinitionCatalog.find(EmployeeService.MODULE_ALIAS))
                .thenReturn(Optional.of(moduleDefinition(EmployeeService.MODULE_ALIAS, "职员管理", Employee.class)));
        when(staticModuleDefinitionCatalog.find(EmployeeAccountService.MODULE_ALIAS))
                .thenReturn(Optional.of(moduleDefinition(EmployeeAccountService.MODULE_ALIAS,
                        "职员账号绑定", EmployeeAccount.class)));
        when(staticModuleDefinitionCatalog.find(UserAccountService.MODULE_ALIAS))
                .thenReturn(Optional.of(moduleDefinition(UserAccountService.MODULE_ALIAS, "用户账号", UserAccount.class)));

        mvc.perform(get("/iam.employee/employee-1/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("binding-1"));

        mvc.perform(post("/iam.employee/employee-1/account")
                        .contentType("application/json")
                        .content("""
                                {"userId":"user-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("binding-1"))
                .andExpect(jsonPath("$.userId").value("user-2"));

        mvc.perform(post("/iam.employee/employee-1/account/provision")
                        .contentType("application/json")
                        .content("""
                                {"username":"alice","password":"secret1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value("user-2"))
                .andExpect(jsonPath("$.data.binding.id").value("binding-1"))
                .andExpect(jsonPath("$.message.code").value("iam.employee-account.provisioned"))
                .andExpect(jsonPath("$.message.text").value("账号已创建并绑定职员"))
                .andExpect(jsonPath("$.message.type").value("SUCCESS"))
                .andExpect(jsonPath("$.changeSetId").isString())
                .andExpect(jsonPath("$.changes[?(@.type == 'record-created' && @.moduleAlias == 'iam.user' && @.recordId == 'user-2')]")
                        .exists())
                .andExpect(jsonPath("$.changes[?(@.type == 'record-created' && @.moduleAlias == 'iam.employee_account' && @.recordId == 'binding-1')]")
                        .exists())
                .andExpect(jsonPath("$.changes[?(@.type == 'record-updated' && @.moduleAlias == 'iam.employee' && @.recordId == 'employee-1')]")
                        .exists());

        mvc.perform(post("/iam.employee/employee-1/account/delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1))
                .andExpect(jsonPath("$.message.code").value("iam.employee-account.removed"))
                .andExpect(jsonPath("$.message.text").value("账户已移除"))
                .andExpect(jsonPath("$.message.type").value("SUCCESS"))
                .andExpect(jsonPath("$.changeSetId").isString())
                .andExpect(jsonPath("$.changes[?(@.type == 'record-deleted' && @.moduleAlias == 'iam.user' && @.recordId == 'user-2')]")
                        .exists())
                .andExpect(jsonPath("$.changes[?(@.type == 'record-deleted' && @.moduleAlias == 'iam.employee_account' && @.recordId == 'binding-1')]")
                        .exists())
                .andExpect(jsonPath("$.changes[?(@.type == 'record-updated' && @.moduleAlias == 'iam.employee' && @.recordId == 'employee-1')]")
                        .exists());

        verify(employeeAccountService).accountOfEmployee("employee-1");
        verify(employeeAccountService).bindAccount(eq("employee-1"), any(EmployeeAccount.class));
        verify(employeeAccountService).provisionAccount(eq("employee-1"), any(UserAccount.class));
        verify(employeeAccountService).removeAccount("employee-1");
    }

    @Test
    void shouldBindEmployeeDelegationEndpointsInRealMvcContext() throws Exception {
        EmployeeDelegation delegation = employeeDelegation("delegation-1", "employee-1", "employee-2");
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(employeeDelegationService.delegationsByPrincipal("employee-1")).thenReturn(List.of(delegation));
        when(employeeDelegationService.delegationsByDelegate("employee-2")).thenReturn(List.of(delegation));
        when(employeeDelegationService.addDelegation(eq("employee-1"), any(EmployeeDelegation.class)))
                .thenReturn("delegation-1");
        when(employeeDelegationService.select("delegation-1")).thenReturn(delegation);
        when(employeeDelegationService.updateDelegation(eq("employee-1"), eq("delegation-1"),
                any(EmployeeDelegation.class))).thenReturn(1);
        when(employeeDelegationService.deleteDelegation("employee-1", "delegation-1")).thenReturn(1);
        when(employeeDelegationService.enableDelegation("employee-1", "delegation-1")).thenReturn(1);
        when(employeeDelegationService.disableDelegation("employee-1", "delegation-1")).thenReturn(1);

        mvc.perform(get("/iam.employee/employee-1/delegations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("delegation-1"))
                .andExpect(jsonPath("$.records[0].delegateEmployeeId").value("employee-2"));

        mvc.perform(get("/iam.employee/employee-2/delegated-to-me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].principalEmployeeId").value("employee-1"));

        mvc.perform(post("/iam.employee/employee-1/delegations")
                        .contentType("application/json")
                        .content("""
                                {"delegateEmployeeId":"employee-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("delegation-1"));

        mvc.perform(post("/iam.employee/employee-1/delegations/delegation-1/update")
                        .contentType("application/json")
                        .content("""
                                {"delegateEmployeeId":"employee-2","memo":"changed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("delegation-1"));

        mvc.perform(post("/iam.employee/employee-1/delegations/delegation-1/delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
        mvc.perform(post("/iam.employee/employee-1/delegations/delegation-1/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
        mvc.perform(post("/iam.employee/employee-1/delegations/delegation-1/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        verify(employeeDelegationService).delegationsByPrincipal("employee-1");
        verify(employeeDelegationService).delegationsByDelegate("employee-2");
        verify(employeeDelegationService).addDelegation(eq("employee-1"), any(EmployeeDelegation.class));
    }

    @Test
    void shouldBindPositionEndpointInRealMvcContext() throws Exception {
        Position position = new Position();
        position.setId("position-1");
        position.setCode("SALES_MANAGER");
        position.setTitle("Sales Manager");
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(positionService.insert(any(Position.class))).thenReturn("position-1");
        when(positionService.select("position-1")).thenReturn(position);

        mvc.perform(get("/iam.position/view/position-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("position-1"))
                .andExpect(jsonPath("$.code").value("SALES_MANAGER"));

        mvc.perform(post("/iam.position/insert")
                        .contentType("application/json")
                        .content("""
                                {"categoryId":"position-category-1","code":"SALES_MANAGER","title":"Sales Manager"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("position-1"))
                .andExpect(jsonPath("$.message.code").value("platform.crud.created"))
                .andExpect(jsonPath("$.message.text").value("「Sales Manager」新增成功"))
                .andExpect(jsonPath("$.changes[?(@.type == 'record-created' && @.moduleAlias == 'iam.position' && @.recordId == 'position-1')]")
                        .exists());
    }

    @Test
    void shouldRejectPostForReadOnlyTreeEndpointInRealMvcContext() throws Exception {
        mvc.perform(post("/iam.organization/tree"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void shouldApplyAdviceWhenCurrentUserTenantIsMissingInRealMvcContext() throws Exception {
        when(currentUserProvider.currentUser()).thenReturn(Optional.empty());

        mvc.perform(get("/iam.organization/tree"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("iam.organization requires tenant context"));
    }

    @Test
    void shouldBindRoleManagementEndpointsInRealMvcContext() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(roleService.select(any())).thenAnswer(invocation -> readableRole((String) invocation.getArgument(0)));
        AccountRoleGrant accountGrant = accountRoleGrant("grant-1", "role-1", "user-2",
                ManagementScopeType.TENANT, "tenant_a");
        EmploymentRoleGrant employmentGrant = employmentRoleGrant("grant-2", "role-2", "position-1");
        when(roleService.grantAccountRoleResult("role-1", "user-2", ManagementScopeType.TENANT, "tenant_a"))
                .thenReturn(new RoleService.RoleGrantMutationResult("grant-1", true));
        when(roleService.grantAccountRoleResult("role-1", "user-3", ManagementScopeType.TENANT, "tenant_a"))
                .thenReturn(new RoleService.RoleGrantMutationResult("grant-existing", false));
        when(roleService.accountRoleGrants("role-1")).thenReturn(List.of(accountGrant));
        when(roleService.deleteAccountRoleGrant("role-1", "grant-1")).thenReturn(1);
        when(roleService.grantEmploymentRoleResult("role-2", "position-1"))
                .thenReturn(new RoleService.RoleGrantMutationResult("grant-2", true));
        when(roleService.employmentRoleGrants("role-2")).thenReturn(List.of(employmentGrant));
        when(roleService.deleteEmploymentRoleGrant("role-2", "grant-2")).thenReturn(1);
        when(roleService.grantAction("role-1", "sales.contract", "query",
                DataScopePolicy.OWNER, TenantScopePolicy.CURRENT_TENANT,
                null, null, null)).thenReturn(1);
        when(roleService.grantAction("role-1", "sales.contract", "query",
                DataScopePolicy.DEPARTMENT_AND_CHILDREN, TenantScopePolicy.CURRENT_TENANT,
                null, null, null)).thenReturn(1);
        when(roleService.revokeAction("role-1", "sales.contract", "query")).thenReturn(1);

        mvc.perform(post("/iam.role/{roleId}/account-grants", "role-1")
                        .contentType("application/json")
                        .content("""
                                {"userId":"user-2","managementScopeType":"tenant","managementScopeId":"tenant_a"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("grant-1"))
                .andExpect(jsonPath("$.message.code").value("iam.account-role-grant.granted"))
                .andExpect(jsonPath("$.message.text").value("账号角色已授权"))
                .andExpect(jsonPath("$.changes[?(@.type == 'collection-changed' && @.moduleAlias == 'iam.role' && @.recordId == null)]")
                        .exists());

        mvc.perform(post("/iam.role/{roleId}/account-grants", "role-1")
                        .contentType("application/json")
                        .content("""
                                {"userId":"user-3","managementScopeType":"tenant","managementScopeId":"tenant_a"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("grant-existing"))
                .andExpect(jsonPath("$.message.code").value("iam.account-role-grant.granted"))
                .andExpect(jsonPath("$.changes").isEmpty());

        mvc.perform(get("/iam.role/{roleId}/account-grants", "role-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("grant-1"))
                .andExpect(jsonPath("$[0].userId").value("user-2"))
                .andExpect(jsonPath("$[0].managementScopeType").value("tenant"));

        mvc.perform(post("/iam.role/{roleId}/account-grants/{grantId}/delete", "role-1", "grant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1))
                .andExpect(jsonPath("$.message.code").value("iam.account-role-grant.revoked"))
                .andExpect(jsonPath("$.message.text").value("账号角色授权已撤销"))
                .andExpect(jsonPath("$.changes[?(@.type == 'collection-changed' && @.moduleAlias == 'iam.role' && @.recordId == null)]")
                        .exists());

        mvc.perform(post("/iam.role/{roleId}/employment-grants", "role-2")
                        .contentType("application/json")
                        .content("""
                                {"employeePositionId":"position-1"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("grant-2"))
                .andExpect(jsonPath("$.message.code").value("iam.employment-role-grant.granted"))
                .andExpect(jsonPath("$.message.text").value("任职角色已授权"))
                .andExpect(jsonPath("$.changes[?(@.type == 'collection-changed' && @.moduleAlias == 'iam.role' && @.recordId == null)]")
                        .exists());

        mvc.perform(get("/iam.role/{roleId}/employment-grants", "role-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("grant-2"))
                .andExpect(jsonPath("$[0].employeePositionId").value("position-1"));

        mvc.perform(post("/iam.role/{roleId}/employment-grants/{grantId}/delete", "role-2", "grant-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1))
                .andExpect(jsonPath("$.message.code").value("iam.employment-role-grant.revoked"))
                .andExpect(jsonPath("$.message.text").value("任职角色授权已撤销"))
                .andExpect(jsonPath("$.changes[?(@.type == 'collection-changed' && @.moduleAlias == 'iam.role' && @.recordId == null)]")
                        .exists());

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

        mvc.perform(post("/iam.role/grant/{roleId}", "role-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "moduleAlias":"sales.contract",
                                  "actionCode":"query",
                                  "dataScopePolicy":"departmentAndChildren",
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
    void shouldResolveRolePermissionMatrixInRealMvcContext() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(roleService.select("role-1")).thenReturn(readableRole("role-1"));
        List<GrantableAction> grantableActions = List.of(
                new GrantableAction("sales.contract", "query", "view", "Query", true, true)
        );
        when(roleGrantableActionResolver.resolve(List.of("sales.contract"))).thenReturn(grantableActions);
        when(roleService.permissionMatrix("role-1", grantableActions)).thenReturn(new RolePermissionMatrix(
                "role-1",
                List.of(new RolePermissionMatrix.Module(
                        "sales.contract",
                        List.of(new RolePermissionAction(
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
                .andExpect(jsonPath("$.modules[0].actions[0].permissionActionCode").value("view"))
                .andExpect(jsonPath("$.modules[0].actions[0].granted").value(true));
    }

    @Test
    void shouldApplyIamAdviceWhenRoleGrantRejectsUnsupportedCustomDataScope() throws Exception {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(roleService.grantAction("role-1", "sales.contract", "query",
                DataScopePolicy.CUSTOM, TenantScopePolicy.CURRENT_TENANT,
                "authUserId = ${userId}", null, null))
                .thenThrow(new PlatformException("custom data scope policy is not supported yet"));

        mvc.perform(post("/iam.role/grant/{roleId}", "role-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "moduleAlias":"sales.contract",
                                  "actionCode":"query",
                                  "dataScopePolicy":"custom",
                                  "tenantScopePolicy":"currentTenant",
                                  "scopeCondition":"authUserId = ${userId}"
                                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("custom data scope policy is not supported yet"));
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

    private Role readableRole(String id) {
        Role role = new Role();
        role.setId(id);
        return role;
    }

    private EmployeeAccount employeeAccount(String id, String employeeId, String userId) {
        EmployeeAccount binding = new EmployeeAccount();
        binding.setId(id);
        binding.setEmployeeId(employeeId);
        binding.setUserId(userId);
        return binding;
    }

    private EmployeeDelegation employeeDelegation(String id, String principalEmployeeId, String delegateEmployeeId) {
        EmployeeDelegation delegation = new EmployeeDelegation();
        delegation.setId(id);
        delegation.setPrincipalEmployeeId(principalEmployeeId);
        delegation.setDelegateEmployeeId(delegateEmployeeId);
        delegation.setEnabled(Boolean.TRUE);
        return delegation;
    }

    private void wireEmployeeQueryAbility() {
        when(employeeService.queryCriteria(any(QueryRequest.class)))
                .thenAnswer(invocation -> employeeQueryCompiler().criteria(invocation.getArgument(0)));
        when(employeeService.querySorts(any(QueryRequest.class)))
                .thenAnswer(invocation -> employeeQueryCompiler().sorts(invocation.getArgument(0)));
    }

    private StaticModuleDefinition employeeStaticModuleDefinition() {
        EmployeeWebController controller = new EmployeeWebController(
                employeePositionService,
                employeeAccountService,
                employeeDelegationService
        );
        return StaticModuleDefinition.builder("iam", EmployeeService.MODULE_ALIAS, "职员管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/employees", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new StaticEntityDefinitionCompiler().compile("employee", "职员管理", Employee.class)))
                       .uiDefinition(controller.moduleUiDefinition())
                       .references(StaticReferenceCompiler.compile(Employee.class))
                       .readProjections(List.of(
                        new StaticModuleReadProjectionDefinition(
                                ReferencePath.from(Employee::getOrganizationId)
                                        .select(Organization::getTitle),
                                "organizationTitle"),
                        new StaticModuleReadProjectionDefinition(
                                null,
                                ReferencePath.inverseOne(EmployeeAccount::getEmployeeId)
                                        .then(EmployeeAccount::getUserId)
                                        .select(UserAccount::getUsername),
                                "username",
                                ModuleReadProjection.ProjectionType.FIELD,
                                true,
                                false),
                        new StaticModuleReadProjectionDefinition(
                                null,
                                ReferencePath.inverseOne(EmployeeAccount::getEmployeeId)
                                        .select(EmployeeAccount::getId),
                                "accountBound",
                                ModuleReadProjection.ProjectionType.EXISTS,
                                true,
                                false)
                ))
                       .modelClass(Employee.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition departmentStaticModuleDefinition() {
        DepartmentWebController controller = new DepartmentWebController();
        return StaticModuleDefinition.builder("iam", DepartmentService.MODULE_ALIAS, "部门管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/departments", null)
                       .capabilities(Set.of(EntityCapability.CRUD, EntityCapability.TREE))
                       .actions(List.of())
                       .entities(List.of(new StaticEntityDefinitionCompiler().compile("department", "部门管理", Department.class)))
                       .uiDefinition(controller.moduleUiDefinition())
                       .build();
    }

    private StaticModuleDefinition moduleDefinition(String moduleAlias, String title, Class<?> modelClass) {
        return StaticModuleDefinition.builder(moduleAlias.substring(0, moduleAlias.indexOf('.')), moduleAlias, title)
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.MODULE, null, null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new StaticEntityDefinitionCompiler().compile(
                        moduleAlias.substring(moduleAlias.indexOf('.') + 1),
                        title,
                        modelClass
                )))
                       .uiDefinition(null)
                       .references(List.of())
                       .readProjections(List.of())
                       .modelClass(modelClass)
                       .projectionJoins(List.of())
                       .build();
    }

    private QueryCompiler employeeQueryCompiler() {
        return new QueryCompiler(employeeQueryDescriptor());
    }

    private QueryDescriptor employeeQueryDescriptor() {
        return QueryDescriptor.builder(EmployeeService.MODULE_ALIAS)
                .field(QueryField.of("id", QueryOperator.EQ, QueryOperator.IN).withTitle("ID"))
                .field(QueryField.of("organizationId", QueryOperator.EQ, QueryOperator.IN).withTitle("所属机构"))
                .field(QueryField.of("departmentId", QueryOperator.EQ, QueryOperator.IN).withTitle("所属部门"))
                .field(QueryField.of("enabled", QueryValueType.BOOLEAN, QueryOperator.EQ).withTitle("启用状态"))
                .field(QueryField.of("employeeNo", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("职员编号").withQuickSearch().withSortable())
                .field(QueryField.of("title", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("职员姓名").withQuickSearch().withSortable())
                .field(QueryField.of("mobile", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("手机号").withQuickSearch())
                .field(QueryField.of("email", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("邮箱").withQuickSearch())
                .field(QueryField.of("sortOrder", QueryValueType.INTEGER, QueryOperator.EQ)
                        .withTitle("排序号").withSortable())
                .field(QueryField.of("createdAt", QueryValueType.INSTANT, QueryOperator.GTE, QueryOperator.LTE,
                                QueryOperator.BETWEEN)
                        .withTitle("创建时间")
                        .withSortable())
                .field(QueryField.of("updatedAt", QueryValueType.INSTANT, QueryOperator.GTE, QueryOperator.LTE,
                                QueryOperator.BETWEEN)
                        .withTitle("更新时间")
                        .withSortable())
                .externalCriteria("departmentScope", this::employeeDepartmentScopeCriteria)
                .defaultSort(Sort.asc("sortOrder"))
                .defaultSort(Sort.asc("employeeNo"))
                .build();
    }

    private Criteria employeeDepartmentScopeCriteria(Object value) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> scope = (java.util.Map<String, Object>) value;
        String organizationId = String.valueOf(scope.get("organizationId"));
        String departmentId = String.valueOf(scope.get("departmentId"));
        boolean includeChildren = Boolean.TRUE.equals(scope.get("includeChildren"));
        Criteria criteria = Criteria.of().eq("organizationId", organizationId);
        if (!includeChildren) {
            return criteria.eq("departmentId", departmentId);
        }
        return criteria.in("departmentId", departmentService.selfAndDescendantIds(organizationId, departmentId));
    }

    private boolean containsCondition(Criteria criteria, String fieldName, Object value) {
        return clauses(criteria).stream()
                .anyMatch(clause -> fieldName.equals(clause.getField())
                        && clause.getValues().contains(value));
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
}
