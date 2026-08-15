package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.spring.platform.module.StaticServiceAbilityCompiler;

import net.ximatai.muyun.spring.platform.module.StaticReferenceDefinition;

import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.platform.web.code.CodeLedgerEntryWebController;
import net.ximatai.muyun.spring.platform.web.code.CodeIssueLogWebController;
import net.ximatai.muyun.spring.platform.web.code.CodeRecycleEntryWebController;
import net.ximatai.muyun.spring.platform.web.code.CodeRuleWebController;
import net.ximatai.muyun.spring.platform.web.code.CodeSequenceStateWebController;
import net.ximatai.muyun.spring.platform.application.PlatformStaticApplication;
import net.ximatai.muyun.spring.iam.web.DepartmentWebController;
import net.ximatai.muyun.spring.iam.web.EmployeeWebController;
import net.ximatai.muyun.spring.iam.web.LoginWebController;
import net.ximatai.muyun.spring.iam.web.OrganizationWebController;
import net.ximatai.muyun.spring.iam.web.PasswordPolicyRuleWebController;
import net.ximatai.muyun.spring.iam.web.PositionCategoryWebController;
import net.ximatai.muyun.spring.iam.web.PositionWebController;
import net.ximatai.muyun.spring.iam.web.RoleWebController;
import net.ximatai.muyun.spring.iam.web.SystemUserAccountWebController;
import net.ximatai.muyun.spring.iam.web.TenantWebController;
import net.ximatai.muyun.spring.iam.web.UserAccountWebController;
import net.ximatai.muyun.spring.platform.web.workflow.WorkflowRuntimeAdminWebController;
import net.ximatai.muyun.spring.platform.web.workflow.WorkflowDefinitionWebController;
import net.ximatai.muyun.spring.platform.web.workflow.WorkflowVersionWebController;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.employee.EmployeeDao;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegationService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.position.PositionCategoryService;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRuleService;
import net.ximatai.muyun.spring.platform.code.CodeRuleService;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.user.PasswordHashingService;
import net.ximatai.muyun.spring.iam.user.CurrentUserProfileService;
import net.ximatai.muyun.spring.iam.user.UserAccountDao;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowActionPolicyService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinitionService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowPublishFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowVersionService;
import net.ximatai.muyun.spring.platform.code.CodePreviewService;
import net.ximatai.muyun.spring.platform.code.CodeOpsActionService;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigArchiveFacade;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleHealthService;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageExchangeService;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageImportService;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleTemplateService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjection;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjectionContributor;
import net.ximatai.muyun.spring.common.measure.MeasureUnitField;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitConversionMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitMode;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class StaticModuleDefinitionScannerTest {

    private static <T> T withService(T controller, Object service) {
        ReflectionTestUtils.setField(controller, "service", service);
        return controller;
    }

    @Test
    void shouldCompilePlatformApplicationAsDescriptorDrivenModuleEntry() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ApplicationWebController.class);
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().stream()
                    .filter(candidate -> ApplicationService.MODULE_ALIAS.equals(candidate.moduleAlias()))
                    .findFirst()
                    .orElseThrow();

            assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
            assertThat(definition.uiDefinition().views()).extracting(ViewDefinition::viewCode)
                    .containsExactly("default_list", "default_form");
            assertThat(definition.uiDefinition().views()).filteredOn(view -> view.viewCode().equals("default_form"))
                    .singleElement()
                    .satisfies(view -> assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("alias"))
                            .singleElement()
                            .satisfies(field -> assertThat(field.readOnly().formula().expression())
                                    .isEqualTo("PRESENT({id})")));
            assertThat(ModuleUiDescriptorCompiler.compile(definition).views())
                    .filteredOn(view -> view.viewCode().equals("default_list"))
                    .singleElement()
                    .satisfies(view -> assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                            .containsExactly("alias", "title", "enabled"));
        }
    }

    @Test
    void shouldScanIamStaticModulesAndActionsFromControllerAnnotations() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(TenantWebController.class,
                    () -> withService(new TenantWebController(), mock(TenantService.class)));
            context.registerBean(OrganizationWebController.class,
                    () -> withService(new OrganizationWebController(), mock(OrganizationService.class)));
            context.registerBean(DepartmentWebController.class,
                    () -> withService(new DepartmentWebController(), mock(DepartmentService.class)));
            EmployeeService employeeService = new EmployeeService(mock(EmployeeDao.class),
                    mock(net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier.class),
                    mock(OrganizationService.class),
                    mock(DepartmentService.class));
            context.registerBean(EmployeeService.class, () -> employeeService);
            context.registerBean(EmployeeWebController.class, () -> {
                EmployeeWebController controller = new EmployeeWebController(mock(EmployeePositionService.class),
                        mock(EmployeeAccountService.class), mock(EmployeeDelegationService.class));
                ReflectionTestUtils.setField(controller, "service", employeeService);
                return controller;
            });
            context.registerBean(PositionWebController.class,
                    () -> withService(new PositionWebController(),
                            mock(net.ximatai.muyun.spring.iam.position.PositionService.class)));
            context.registerBean(PositionCategoryWebController.class,
                    () -> withService(new PositionCategoryWebController(), mock(PositionCategoryService.class)));
            context.registerBean(RoleWebController.class,
                    () -> withService(new RoleWebController(null), mock(RoleService.class)));
            UserAccountService userAccountService = net.ximatai.muyun.spring.iam.support.UserAccountServiceTestFactory.create(mock(UserAccountDao.class),
                    mock(net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier.class),
                    new PasswordHashingService());
            context.registerBean(UserAccountService.class, () -> userAccountService);
            context.registerBean(UserAccountWebController.class, () -> {
                UserAccountWebController controller = new UserAccountWebController();
                ReflectionTestUtils.setField(controller, "service", userAccountService);
                return controller;
            });
            context.registerBean(LoginWebController.class, () -> new LoginWebController(
                    mock(UserSessionService.class), mock(TenantService.class), mock(CurrentUserProfileService.class)));
            context.registerBean(SystemUserAccountWebController.class);
            context.registerBean(PasswordPolicyRuleWebController.class,
                    () -> withService(new PasswordPolicyRuleWebController(), mock(PasswordPolicyRuleService.class)));
            context.refresh();
            StaticModuleDefinitionScanner scanner = new StaticModuleDefinitionScanner(context);

            List<StaticModuleDefinition> definitions = scanner.scan();
            Map<String, StaticModuleDefinition> byAlias = definitions.stream()
                    .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));

            assertThat(byAlias.keySet()).containsExactlyInAnyOrder(
                    "iam.tenant", "iam.organization", "iam.department", "iam.employee",
                    "iam.position_category", "iam.role", "iam.user", "iam.system_user",
                    "iam.password_policy_rule");
            assertThat(byAlias.get("iam.tenant")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("租户管理");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactly("menu", "create", "view", "update", "delete", "query",
                                "sort", "enable", "disable", "recycleBinQuery", "recycleBinRestore");
            });
            assertThat(byAlias.get("iam.organization")).satisfies(definition -> {
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .doesNotContain("recycleBinQuery", "recycleBinRestore");
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("机构管理");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.ROUTE);
                assertThat(definition.entryRoute()).isEqualTo("/iam/organizations");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactly("menu", "create", "view", "update", "delete", "query",
                                "tree", "sort", "enable", "disable");
            });
            assertThat(byAlias.get("iam.department")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("部门管理");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.ROUTE);
                assertThat(definition.entryRoute()).isEqualTo("/iam/departments");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                                "tree", "sort", "enable", "disable");
            });
            assertThat(byAlias.get("iam.employee")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("职员管理");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.ROUTE);
                assertThat(definition.entryRoute()).isEqualTo("/iam/employees");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                                "sort", "enable", "disable", "employeePositions", "employeeAccounts",
                                "employeeDelegations", "employeeDelegatedToMe",
                                "recycleBinQuery", "recycleBinRestore");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .doesNotContain("recycleBinPurge");
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("employeeAccounts"))
                        .singleElement()
                        .satisfies(action -> assertCustomRecordAction(action, "employeeAccounts", "职员账号"));
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("employeePositions"))
                        .singleElement()
                        .satisfies(action -> assertCustomRecordAction(action, "employeePositions", "职员任岗"));
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("employeeDelegations"))
                        .singleElement()
                        .satisfies(action -> assertCustomRecordAction(action, "employeeDelegations", "职员业务代办"));
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("employeeDelegatedToMe"))
                        .singleElement()
                        .satisfies(action -> assertCustomRecordAction(action, "employeeDelegatedToMe", "职员受托代办"));
                assertThat(definition.references()).extracting(StaticReferenceDefinition::code)
                        .containsExactly("organization", "department");
                assertThat(definition.references()).extracting(StaticReferenceDefinition::targetModuleAlias)
                        .containsExactly("iam.organization", "iam.department");
                assertThat(definition.readProjections()).extracting(StaticModuleReadProjectionDefinition::path)
                        .containsExactly("organization.title", null, null);
                assertThat(definition.readProjections()).filteredOn(projection -> projection.referencePath() != null)
                        .extracting(projection ->
                        projection.referencePath().steps().getFirst().referenceField().fieldName())
                        .containsExactly("employeeId", "employeeId");
                assertThat(definition.readProjections()).filteredOn(projection -> projection.referencePath() != null)
                        .extracting(projection ->
                        projection.referencePath().targetField().fieldName())
                        .containsExactly("username", "id");
                assertThat(definition.readProjections()).extracting(StaticModuleReadProjectionDefinition::outputField)
                        .containsExactly("organizationTitle", "username", "accountBound");
                assertThat(definition.uiDefinition()).isNotNull();
                assertThat(definition.uiDefinition().views()).hasSize(2);
                assertThat(definition.uiDefinition().views()).filteredOn(view -> view.viewCode().equals("default_list"))
                        .singleElement()
                        .satisfies(view -> {
                            assertThat(view.viewKind()).isEqualTo(ModuleViewKind.LIST);
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .containsExactly("employeeNo", "organizationTitle", "title", "username",
                                            "mobile", "email", "enabled", "avatarAssetId", "accountBound");
                        });
                assertThat(definition.uiDefinition().views()).filteredOn(view -> view.viewCode().equals("default_form"))
                        .singleElement()
                        .satisfies(view -> {
                            assertThat(view.viewKind()).isEqualTo(ModuleViewKind.FORM);
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .containsExactly("organizationId", "departmentId", "employeeNo", "title",
                                            "gender", "mobile", "email", "enabled");
                            assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("departmentId"))
                                    .singleElement()
                                    .satisfies(field -> assertThat(field.uiType()).isEqualTo("recordPicker"));
                        });
            });
            assertThat(byAlias.get("iam.position_category")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("岗位管理");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.ROUTE);
                assertThat(definition.entryRoute()).isEqualTo("/iam/positions");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                                "tree", "sort", "enable", "disable",
                                "position_create", "position_view", "position_update", "position_delete",
                                "position_query", "position_sort", "position_enable", "position_disable");
                assertThat(definition.actions())
                        .filteredOn(action -> action.actionCode().equals("position_query"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.permissionActionCode()).isEqualTo("position_view");
                            assertThat(action.title()).isEqualTo("查询岗位");
                        });
                assertThat(definition.uiDefinition()).isNotNull();
                assertThat(definition.uiDefinition().views())
                        .filteredOn(view -> view.viewCode()
                                .equals(ModuleUiViewCodes.childResourceDefaultForm("position")))
                        .singleElement()
                        .satisfies(view -> {
                            assertThat(view.viewKind()).isEqualTo(ModuleViewKind.FORM);
                            assertThat(view.fields()).extracting(field -> field.fieldRef().relationCode())
                                    .containsExactly("position", "position", "position", "position", "position");
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .containsExactly("categoryId", "code", "title", "description", "enabled");
                            assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("categoryId"))
                                    .singleElement()
                                    .satisfies(field -> {
                                        assertThat(field.label()).isEqualTo("所属分类");
                                        assertThat(field.required().constant()).isTrue();
                                    });
                            assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("enabled"))
                                    .singleElement()
                                    .satisfies(field -> assertThat(field.uiType()).isEqualTo("enabledStatus"));
                        });
            });
            assertThat(byAlias.get("iam.role")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("角色管理");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                                "sort", "enable", "disable", "accountRoleGrants", "employmentRoleGrants",
                                "rolePermissions");
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("accountRoleGrants"))
                        .singleElement()
                        .satisfies(action -> assertCustomRecordAction(action, "accountRoleGrants", "账号角色授权"));
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("employmentRoleGrants"))
                        .singleElement()
                        .satisfies(action -> assertCustomRecordAction(action, "employmentRoleGrants", "任职角色授权"));
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("rolePermissions"))
                        .singleElement()
                        .satisfies(action -> assertCustomRecordAction(action, "rolePermissions", "角色授权"));
                assertThat(definition.uiDefinition()).isNotNull();
                assertThat(definition.uiDefinition().views()).hasSize(2);
                assertThat(definition.uiDefinition().views()).filteredOn(view -> view.viewCode().equals("default_list"))
                        .singleElement()
                        .satisfies(view -> {
                            assertThat(view.viewKind()).isEqualTo(ModuleViewKind.LIST);
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .containsExactly("title", "assignmentType", "roleKind", "sharePolicy",
                                            "systemManaged", "enabled");
                            assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("enabled"))
                                    .singleElement()
                                    .satisfies(field -> assertThat(field.uiType()).isEqualTo("enabledStatus"));
                        });
                assertThat(definition.uiDefinition().views()).filteredOn(view -> view.viewCode().equals("default_form"))
                        .singleElement()
                        .satisfies(view -> {
                            assertThat(view.viewKind()).isEqualTo(ModuleViewKind.FORM);
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .containsExactly("title", "assignmentType", "roleKind", "memberRoleIds",
                                            "ownerScopeType", "ownerScopeId", "sharePolicy", "description",
                                            "enabled", "sortOrder");
                            assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("ownerScopeType"))
                                    .singleElement()
                                    .satisfies(field -> {
                                        assertThat(field.required().constant()).isTrue();
                                        assertThat(field.readOnly().constant()).isTrue();
                                        assertThat(field.uiType()).isEqualTo("select");
                                    });
                        });
            });
            assertThat(byAlias.get("iam.user")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.moduleAlias()).isEqualTo("iam.user");
                assertThat(definition.title()).isEqualTo("用户管理");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                                "enable", "disable", "userSelector", "changePassword", "resetPassword",
                                "forceLogout", "sessions", "sessionStatuses", "revokeSession", "revokeSessions",
                                "employeeBinding", "selfProfile", "loginContext");
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("selfProfile"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.accessMode()).isEqualTo(EntityActionAccessMode.LOGIN_REQUIRED);
                            assertThat(action.actionAuth()).isFalse();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("loginContext"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.accessMode()).isEqualTo(EntityActionAccessMode.ANONYMOUS_ALLOWED);
                            assertThat(action.actionAuth()).isFalse();
                            assertThat(action.dataAuth()).isFalse();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("userSelector"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.title()).isEqualTo("用户选择器");
                            assertThat(action.actionLevel()).isEqualTo(EntityActionLevel.LIST);
                            assertThat(action.dataAuth()).isTrue();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("changePassword"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.title()).isEqualTo("修改密码");
                            assertThat(action.dataAuth()).isTrue();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("resetPassword"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.title()).isEqualTo("重置密码");
                            assertThat(action.dataAuth()).isTrue();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("forceLogout"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.title()).isEqualTo("强制下线");
                            assertThat(action.dataAuth()).isTrue();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("sessions"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.title()).isEqualTo("在线会话");
                            assertThat(action.dataAuth()).isTrue();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("sessionStatuses"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.title()).isEqualTo("在线状态");
                            assertThat(action.actionLevel()).isEqualTo(EntityActionLevel.LIST);
                            assertThat(action.dataAuth()).isTrue();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("revokeSession"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.title()).isEqualTo("下线会话");
                            assertThat(action.dataAuth()).isTrue();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("revokeSessions"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.title()).isEqualTo("批量下线会话");
                            assertThat(action.dataAuth()).isTrue();
                        });
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("employeeBinding"))
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.title()).isEqualTo("绑定职员");
                            assertThat(action.dataAuth()).isTrue();
                        });
                assertThat(definition.entities()).extracting(EntityDefinition::alias)
                        .containsExactly("user");
                assertThat(definition.projectionJoins()).isEmpty();
                assertThat(definition.readProjections()).extracting(StaticModuleReadProjectionDefinition::path)
                        .containsOnlyNulls();
                assertThat(definition.readProjections()).extracting(projection ->
                        projection.referencePath().steps().stream()
                                .map(step -> step.referenceField().fieldName())
                                .toList())
                        .containsExactly(
                                List.of("userId", "employeeId"),
                                List.of("userId", "employeeId"),
                                List.of("userId"),
                                List.of("userId", "employeeId"),
                                List.of("userId", "employeeId", "organizationId"),
                                List.of("userId", "employeeId"),
                                List.of("userId", "employeeId", "departmentId")
                        );
                assertThat(definition.readProjections()).extracting(projection ->
                        projection.referencePath().targetField().fieldName())
                        .containsExactly("employeeNo", "title", "employeeId", "organizationId", "title",
                                "departmentId", "title");
                assertThat(definition.readProjections()).extracting(StaticModuleReadProjectionDefinition::outputField)
                        .containsExactly("employeeNo", "employeeTitle", "employeeId", "employeeOrganizationId",
                                "organizationTitle", "employeeDepartmentId", "departmentTitle");
                assertThat(definition.readProjections()).extracting(StaticModuleReadProjectionDefinition::filterable)
                        .containsExactly(true, false, false, false, false, false, false);
                assertThat(definition.readProjections()).extracting(StaticModuleReadProjectionDefinition::sortable)
                        .containsExactly(true, true, true, true, true, true, true);
                assertThat(definition.uiDefinition()).isNotNull();
                assertThat(definition.uiDefinition().views()).filteredOn(view -> view.viewCode().equals("default_list"))
                        .singleElement()
                        .satisfies(view -> {
                            assertThat(view.fields()).extracting(field -> field.fieldRef().relationCode())
                                    .containsOnlyNulls();
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .contains("username", "employeeNo", "employeeTitle")
                                    .doesNotContain("onlineStatus");
                        });
            });
            assertThat(byAlias.get("iam.system_user")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.moduleAlias()).isEqualTo("iam.system_user");
                assertThat(definition.title()).isEqualTo("系统账号管理");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.ROUTE);
                assertThat(definition.entryRoute()).isEqualTo("/iam/system-users");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactly("menu");
            });
            assertThat(byAlias.get("iam.password_policy_rule")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.moduleAlias()).isEqualTo("iam.password_policy_rule");
                assertThat(definition.title()).isEqualTo("密码策略规则");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactly("menu", "create", "view", "update", "delete", "query",
                                "sort", "enable", "disable");
            });
        }
    }

    @Test
    void shouldScanCodeRuleAndReadOnlyLifecycleModules() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(CodeRuleWebController.class,
                    () -> withService(
                            new CodeRuleWebController(org.mockito.Mockito.mock(CodePreviewService.class)),
                            mock(CodeRuleService.class)));
            context.registerBean(CodeSequenceStateWebController.class,
                    () -> new CodeSequenceStateWebController(org.mockito.Mockito.mock(CodeOpsActionService.class)));
            context.registerBean(CodeLedgerEntryWebController.class);
            context.registerBean(CodeRecycleEntryWebController.class);
            context.registerBean(CodeIssueLogWebController.class);
            context.refresh();
            StaticModuleDefinitionScanner scanner = new StaticModuleDefinitionScanner(context);

            Map<String, StaticModuleDefinition> byAlias = scanner.scan().stream()
                    .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));

            assertThat(byAlias.keySet()).containsExactlyInAnyOrder(
                    "platform.code_rule",
                    "platform.code_sequence_state",
                    "platform.code_ledger_entry",
                    "platform.code_recycle_entry",
                    "platform.code_issue_log");
            assertThat(byAlias.get("platform.code_rule").actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("menu", "view", "query",
                            "sort", "enable", "disable", "viewTree", "saveTree", "preview", "opsQuery", "opsManage",
                            "opsRecordQuery", "opsRecordManage");
            assertThat(byAlias.get("platform.code_sequence_state").actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("menu", "view", "query", "adjustBaseline");
            assertThat(byAlias.get("platform.code_sequence_state").actions())
                    .filteredOn(action -> action.actionCode().equals("adjustBaseline"))
                    .singleElement()
                    .satisfies(action -> {
                        assertThat(action.actionLevel()).isEqualTo(EntityActionLevel.RECORD);
                        assertThat(action.actionAuth()).isTrue();
                        assertThat(action.dataAuth()).isTrue();
                        assertThat(action.defaultGrantPolicy()).isEqualTo(ActionDefaultGrantPolicy.NONE);
                    });
            assertThat(byAlias.get("platform.code_ledger_entry").actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactly("menu", "view", "query");
            assertThat(byAlias.get("platform.code_recycle_entry").actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactly("menu", "view", "query");
            assertThat(byAlias.get("platform.code_issue_log").actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactly("menu", "view", "query");
        }
    }

    @Test
    void shouldScanMenuMaintenanceModules() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(DictionaryCategoryService.class,
                    () -> new DictionaryCategoryService(mock(BaseDao.class)));
            context.registerBean(DictionaryItemService.class,
                    () -> new DictionaryItemService(mock(BaseDao.class), context.getBean(DictionaryCategoryService.class)));
            context.registerBean(MenuSchemeWebController.class,
                    () -> withService(new MenuSchemeWebController(), mock(MenuSchemeService.class)));
            context.registerBean(MenuManagementWebController.class,
                    () -> withService(new MenuManagementWebController(),
                            mock(net.ximatai.muyun.spring.platform.menu.MenuService.class)));
            context.registerBean(DictionaryCategoryWebController.class, () -> {
                DictionaryCategoryWebController controller = new DictionaryCategoryWebController();
                ReflectionTestUtils.setField(controller, "service", context.getBean(DictionaryCategoryService.class));
                return controller;
            });
            context.registerBean(DictionaryItemWebController.class, () -> {
                DictionaryItemWebController controller = new DictionaryItemWebController();
                ReflectionTestUtils.setField(controller, "service", context.getBean(DictionaryItemService.class));
                return controller;
            });
            context.refresh();
            StaticModuleDefinitionScanner scanner = new StaticModuleDefinitionScanner(context);

            Map<String, StaticModuleDefinition> byAlias = scanner.scan().stream()
                    .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));

            assertThat(byAlias.keySet()).containsExactlyInAnyOrder(
                    "platform.menu_scheme", "platform.menu", "platform.dictionary_category");
            assertThat(byAlias.get("platform.menu_scheme").actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                            "sort", "enable", "disable");
            assertThat(byAlias.get("platform.menu").actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("create", "view", "update", "delete", "query",
                            "tree", "sort", "enable", "disable");
            assertThat(byAlias.get("platform.dictionary_category").actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                            "tree", "sort", "enable", "disable",
                            "item_create", "item_view", "item_update", "item_delete", "item_query",
                            "item_tree", "item_sort", "item_enable", "item_disable");
            assertThat(byAlias.get("platform.dictionary_category").actions())
                    .filteredOn(action -> action.actionCode().equals("item_query"))
                    .singleElement()
                    .satisfies(action -> {
                        assertThat(action.permissionActionCode()).isEqualTo("item_view");
                        assertThat(action.title()).isEqualTo("查询字典项");
                    });
            assertThat(byAlias.get("platform.dictionary_category").entities())
                    .extracting(EntityDefinition::alias)
                    .containsExactly("dictionary_category", "item");
            assertThat(byAlias.get("platform.dictionary_category").uiDefinition()).isNotNull();
            assertThat(byAlias.get("platform.dictionary_category").uiDefinition().views())
                    .extracting(ViewDefinition::viewCode)
                    .containsExactly(ModuleUiViewCodes.DEFAULT_FORM,
                            ModuleUiViewCodes.childResourceDefaultForm("item"));
            assertThat(byAlias.get("platform.dictionary_category").uiDefinition().views())
                    .filteredOn(view -> view.viewCode().equals("default_form"))
                    .singleElement()
                    .satisfies(view -> {
                        assertThat(view.viewKind()).isEqualTo(ModuleViewKind.FORM);
                        assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                .containsExactly("applicationAlias", "alias", "categoryKind", "title", "enabled");
                        assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("categoryKind"))
                                .singleElement()
                                .satisfies(field -> assertThat(field.uiType()).isEqualTo("select"));
                    });
            assertThat(byAlias.get("platform.dictionary_category").uiDefinition().views())
                    .filteredOn(view -> view.viewCode().equals(ModuleUiViewCodes.childResourceDefaultForm("item")))
                    .singleElement()
                    .satisfies(view -> {
                        assertThat(view.viewKind()).isEqualTo(ModuleViewKind.FORM);
                        assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                .containsExactly("categoryId", "code", "title", "parentId", "enabled");
                        assertThat(view.fields()).extracting(field -> field.fieldRef().relationCode())
                                .containsOnly("item");
                        assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("parentId"))
                                .singleElement()
                                .satisfies(field -> assertThat(field.uiType()).isEqualTo("recordPicker"));
                        assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("enabled"))
                                .singleElement()
                                .satisfies(field -> assertThat(field.uiType()).isEqualTo("enabledStatus"));
                    });
        }
    }

    @Test
    void shouldRejectActionContributionConflictingWithTargetModuleAction() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ConflictingDictionaryCategoryWeb.class);
            context.registerBean(DictionaryItemWebController.class);
            context.refresh();
            StaticModuleDefinitionScanner scanner = new StaticModuleDefinitionScanner(context);

            assertThatThrownBy(scanner::scan)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("action conflicts with target module")
                    .hasMessageContaining("platform.dictionary_category.item_query");
        }
    }

    @Test
    void shouldAllowActionScopeToReuseIdenticalTargetModuleAction() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ScopedActionTargetWeb.class);
            context.registerBean(ScopedCreateActionWeb.class);
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo("demo.scoped_action");
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactly("create");
        }
    }

    @Test
    void shouldRejectActionScopeWhenSameActionCodeHasDifferentPolicy() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ScopedActionTargetWeb.class);
            context.registerBean(ScopedCreateActionWithConflictingPolicyWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("action conflicts with target module")
                    .hasMessageContaining("demo.scoped_action.create");
        }
    }

    @Test
    void shouldRejectActionScopeWhenTargetModuleDoesNotDeclareAction() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ScopedActionTargetWithoutCreateWeb.class);
            context.registerBean(ScopedCreateActionWithoutTargetActionWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@PlatformStaticActionScope action is not declared by target module")
                    .hasMessageContaining("demo.scoped_action_without_create.create");
        }
    }

    @Test
    void shouldRejectControllerWithMultipleStaticActionEndpointOrigins() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ConflictingActionEndpointOriginsWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("static action endpoint origin annotations are mutually exclusive")
                    .hasMessageContaining("@PlatformStaticActionDeclaration")
                    .hasMessageContaining("@PlatformStaticActionScope");
        }
    }

    @Test
    void shouldRejectIdenticalActionDefinitionsFromMultipleContributions() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ContributedActionTargetWeb.class);
            context.registerBean(FirstContributedActionWeb.class);
            context.registerBean(SecondContributedActionWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@PlatformStaticActionContribution action conflicts with target module")
                    .hasMessageContaining("demo.contributed_action.child_run");
        }
    }

    @Test
    void shouldRejectConflictingCustomActionDefinitionsWithinStaticModuleController() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ConflictingCustomActionDefinitionsWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@CustomActionEndpoint action conflicts within controller")
                    .hasMessageContaining(".run <- execute");
        }
    }

    @Test
    void shouldScanRecordLinkageRuleConfigurationModules() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RecordGenerationRuleWebController.class,
                    () -> withService(new RecordGenerationRuleWebController(),
                            mock(net.ximatai.muyun.spring.platform.generation.RecordGenerationRuleService.class)));
            context.registerBean(RecordWriteBackRuleWebController.class,
                    () -> withService(new RecordWriteBackRuleWebController(),
                            mock(net.ximatai.muyun.spring.platform.writeback.RecordWriteBackRuleService.class)));
            context.refresh();
            StaticModuleDefinitionScanner scanner = new StaticModuleDefinitionScanner(context);

            Map<String, StaticModuleDefinition> byAlias = scanner.scan().stream()
                    .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));

            assertThat(byAlias.keySet()).containsExactlyInAnyOrder(
                    "platform.record_generation_rule", "platform.record_write_back_rule");
            assertThat(byAlias.get("platform.record_generation_rule").actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("query", "delete", "enable", "disable",
                            "sort", "viewTree", "saveTree");
            assertThat(byAlias.get("platform.record_write_back_rule").actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("query", "delete", "enable", "disable",
                            "sort", "viewTree", "saveTree");
        }
    }

    @Test
    void shouldAssembleWorkflowActionsFromStaticModuleCapabilities() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(WorkflowEnabledWeb.class);
            context.refresh();
            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo("sales.contract");
            assertThat(definition.supports(EntityCapability.WORKFLOW)).isTrue();
            assertThat(definition.supports(EntityCapability.APPROVAL)).isTrue();
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactly("submitApproval");
            assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("submitApproval"))
                    .singleElement()
                    .satisfies(action -> {
                        assertThat(action.category()).isEqualTo(EntityActionCategory.WORKFLOW);
                        assertThat(action.actionLevel()).isEqualTo(EntityActionLevel.RECORD);
                        assertThat(action.executorType()).isEqualTo(EntityActionExecutorType.SERVICE);
                        assertThat(action.executorKey()).isEqualTo("platform.workflow");
                        assertThat(action.dataAuth()).isFalse();
                    });
        }
    }

    @Test
    void shouldScanWorkflowAdminManagementActions() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(WorkflowRuntimeAdminWebController.class,
                    () -> new WorkflowRuntimeAdminWebController(null));
            context.refresh();
            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.applicationAlias()).isEqualTo("platform");
            assertThat(definition.moduleAlias()).isEqualTo(WorkflowActionPolicyService.MANAGEMENT_MODULE_ALIAS);
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder(
                            "menu",
                            WorkflowActionPolicyService.MANAGEMENT_QUERY_ACTION,
                            WorkflowActionPolicyService.MANAGEMENT_TODO_TASK_QUERY_ACTION,
                            WorkflowActionPolicyService.MANAGEMENT_FORCE_APPROVE_ACTION,
                            WorkflowActionPolicyService.MANAGEMENT_FORCE_TERMINATE_ACTION,
                            WorkflowActionPolicyService.MANAGEMENT_RESET_ACTION,
                            WorkflowActionPolicyService.MANAGEMENT_DELETE_HISTORY_ACTION);
            assertThat(definition.actions()).allSatisfy(action -> {
                assertThat(action.actionLevel()).isEqualTo(EntityActionLevel.LIST);
                assertThat(action.actionAuth()).isTrue();
                assertThat(action.dataAuth()).isFalse();
            });
        }
    }

    @Test
    void shouldScanWorkflowConfigurationModules() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(WorkflowDefinitionWebController.class,
                    () -> withService(
                            new WorkflowDefinitionWebController(
                                    mock(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class),
                                    mock(WorkflowPublishFacade.class)),
                            mock(WorkflowDefinitionService.class)));
            context.registerBean(WorkflowVersionWebController.class,
                    () -> new WorkflowVersionWebController(mock(WorkflowDefinitionService.class)));
            context.refresh();
            StaticModuleDefinitionScanner scanner = new StaticModuleDefinitionScanner(context);

            Map<String, StaticModuleDefinition> byAlias = scanner.scan().stream()
                    .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));

            assertThat(byAlias.keySet()).containsExactlyInAnyOrder(
                    WorkflowDefinitionService.MODULE_ALIAS, WorkflowVersionService.MODULE_ALIAS);
            assertThat(byAlias.get(WorkflowDefinitionService.MODULE_ALIAS).actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("create", "view", "update", "delete", "query", "sort",
                            "publishWorkflowDefinition", "disableWorkflowDefinition", "archiveWorkflowDefinition");
            assertThat(byAlias.get(WorkflowVersionService.MODULE_ALIAS).actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("create", "view", "update", "delete", "query");
        }
    }

    @Test
    void shouldUseLastModuleSegmentAsStaticEntityAlias() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(MultiSegmentModuleWeb.class, () -> new MultiSegmentModuleWeb(new MultiSegmentModuleService()));
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo("platform.workflow.definition");
            assertThat(definition.entities()).singleElement()
                    .satisfies(entity -> assertThat(entity.alias()).isEqualTo("definition"));
        }
    }

    @Test
    void shouldRejectDeclaredReadProjectionWithoutModelReadFact() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(DeclaredProjectionWithoutReferenceWeb.class,
                    () -> new DeclaredProjectionWithoutReferenceWeb(new DeclaredProjectionWithoutReferenceService()));
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("declared read projection requires exactly one direct @ReferenceLoad")
                    .hasMessageContaining("demo.declared_projection_without_reference.organizationTitle");
        }
    }

    @Test
    void shouldScanSnakeCaseWebScopeForCamelCaseStaticAlias() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(FieldSpecWebController.class,
                    () -> withService(new FieldSpecWebController(), mock(FieldSpecService.class)));
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo("platform.field_spec");
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactly("menu", "create", "view", "update", "delete", "query",
                            "sort", "enable", "disable");
        }
    }

    @Test
    void shouldScanNestedResourceControllerActionsFromInheritedEndpoints() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PlatformUiSetWebController.class,
                    () -> withService(new PlatformUiSetWebController(),
                            mock(net.ximatai.muyun.spring.platform.ui.PlatformUiSetService.class)));
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo("platform.ui_set");
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("query", "view", "create", "update", "delete",
                            "enable", "disable", "sort");
        }
    }

    @Test
    void shouldScanFieldUiControlNestedConfigurationActions() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(FieldUiControlPropertyWebController.class,
                    () -> withService(new FieldUiControlPropertyWebController(),
                            mock(net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService.class)));
            context.registerBean(FieldUiControlBindingWebController.class,
                    () -> withService(new FieldUiControlBindingWebController(),
                            mock(net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService.class)));
            context.refresh();

            Map<String, StaticModuleDefinition> byAlias = new StaticModuleDefinitionScanner(context).scan().stream()
                    .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));

            assertThat(byAlias.keySet()).containsExactlyInAnyOrder(
                    "platform.field_ui_control_property",
                    "platform.field_ui_control_binding");
            assertThat(byAlias.get("platform.field_ui_control_property").actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("query", "view", "create", "update", "delete", "sort");
            assertThat(byAlias.get("platform.field_ui_control_binding").actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("query", "view", "create", "update", "delete", "sort");
        }
    }

    @Test
    void shouldRejectStaticModuleScopeWhenSeparatorsAreMissing() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(MissingSeparatorAliasWeb.class);
            context.refresh();
            StaticModuleDefinitionScanner scanner = new StaticModuleDefinitionScanner(context);

            assertThatThrownBy(scanner::scan)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("module-alias web scope");
        }
    }

    @Test
    void shouldRegisterPageConfigPublishActionsAsRecordActions() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PlatformPageConfigPublishWebController.class);
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo("platform.page_config_publish");
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("publishUiConfig", "unpublishUiConfig",
                            "publishQueryTemplate", "unpublishQueryTemplate");
            assertThat(definition.actions()).allSatisfy(action ->
                    assertThat(action.actionLevel()).isEqualTo(EntityActionLevel.RECORD));
        }
    }

    @Test
    void shouldRegisterLowCodeGovernanceActionsAsStaticModuleActions() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(LowCodeModuleConfigArchiveFacade.class,
                    () -> mock(LowCodeModuleConfigArchiveFacade.class));
            context.registerBean(LowCodeModuleHealthService.class, () -> mock(LowCodeModuleHealthService.class));
            context.registerBean(LowCodeModulePackageExchangeService.class,
                    () -> mock(LowCodeModulePackageExchangeService.class));
            context.registerBean(LowCodeModulePackageImportService.class,
                    () -> mock(LowCodeModulePackageImportService.class));
            context.registerBean(LowCodeModuleTemplateService.class,
                    () -> mock(LowCodeModuleTemplateService.class));
            context.registerBean(LowCodeGovernanceWebController.class);
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo("platform.low_code_governance");
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("menu",
                            "checkPackageHealth", "archivePackage", "switchCurrentPackageVersion",
                            "exportCurrentPackage", "exportVersionPackage", "dryRunImportPackage",
                            "prepareImportDraft", "archiveImportDraft",
                            "createTemplateFromVersion", "instantiateTemplate");
            Map<String, StaticModuleActionDefinition> actions = definition.actions().stream()
                    .collect(Collectors.toMap(StaticModuleActionDefinition::actionCode, Function.identity()));
            assertThat(actions.get("checkPackageHealth").actionLevel()).isEqualTo(EntityActionLevel.LIST);
            assertThat(actions.get("archivePackage").actionLevel()).isEqualTo(EntityActionLevel.LIST);
            assertThat(actions.get("dryRunImportPackage").actionLevel()).isEqualTo(EntityActionLevel.LIST);
            assertThat(actions.get("prepareImportDraft").actionLevel()).isEqualTo(EntityActionLevel.LIST);
            assertThat(actions.get("archiveImportDraft").actionLevel()).isEqualTo(EntityActionLevel.LIST);
            assertThat(actions.get("createTemplateFromVersion").actionLevel()).isEqualTo(EntityActionLevel.LIST);
            assertThat(actions.get("instantiateTemplate").actionLevel()).isEqualTo(EntityActionLevel.LIST);
            assertThat(actions.get("switchCurrentPackageVersion").actionLevel()).isEqualTo(EntityActionLevel.RECORD);
            assertThat(actions.get("exportCurrentPackage").actionLevel()).isEqualTo(EntityActionLevel.RECORD);
            assertThat(actions.get("exportVersionPackage").actionLevel()).isEqualTo(EntityActionLevel.RECORD);
        }
    }

    @Test
    void shouldCompileStaticServiceModelMeasureUnitFieldsIntoModuleDefinition() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            StaticMeasureOrderService service = new StaticMeasureOrderService();
            context.registerBean(StaticMeasureOrderWeb.class, () -> new StaticMeasureOrderWeb(service));
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo("sales.order_line");
            assertThat(definition.entities()).singleElement().satisfies(entity -> {
                assertThat(entity.alias()).isEqualTo("order_line");
                assertThat(entity.tableName()).isEqualTo("sales_order_line");
                assertThat(entity.fields()).extracting("fieldName")
                        .contains("quantity", "quantityUnit", "quantityBase", "skuId");
                assertThat(entity.fields()).filteredOn(field -> field.fieldName().equals("quantity"))
                        .singleElement()
                        .satisfies(field -> assertThat(field.measureUnit()).satisfies(measureUnit -> {
                            assertThat(measureUnit.categoryAlias()).isEqualTo("quantity");
                            assertThat(measureUnit.mode()).isEqualTo(FieldMeasureUnitMode.SELECTABLE);
                            assertThat(measureUnit.unitFieldName()).isEqualTo("quantityUnit");
                            assertThat(measureUnit.baseValueFieldName()).isEqualTo("quantityBase");
                            assertThat(measureUnit.baseUnitCode()).isEqualTo("bottle");
                            assertThat(measureUnit.conversionMode()).isEqualTo(FieldMeasureUnitConversionMode.BUSINESS_RULE);
                            assertThat(measureUnit.conversionScopeFieldName()).isEqualTo("skuId");
                        }));
            });
        }
    }

    @Test
    void shouldCompileCapabilitiesAndStandardActionsFromServiceWithoutWebAbilityInterfaces() {
        Object service = mock(CrudAbility.class, withSettings().extraInterfaces(
                EnableAbility.class, SortAbility.class, RecycleBinAbility.class));
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ServiceDeclaredAbilityWeb.class, () -> new ServiceDeclaredAbilityWeb(service));
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.capabilities()).contains(
                    EntityCapability.CRUD,
                    EntityCapability.ENABLE,
                    EntityCapability.SORT,
                    EntityCapability.SOFT_DELETE,
                    EntityCapability.RECYCLE_BIN
            );
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("enable", "disable", "sort",
                            "recycleBinQuery", "recycleBinRestore");
        }
    }

    @Test
    void shouldRejectServiceAbilityRedeclaredByStaticModuleAnnotation() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RedeclaredServiceAbilityWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not redeclare service ability")
                    .hasMessageContaining("demo.redeclared.ENABLE");
        }
    }

    @Test
    void shouldSubtractConcreteServiceDisabledOperationsFromCanonicalAbilityMethods() {
        assertThat(StaticServiceAbilityCompiler.operationMethods(new ReadOnlyOperationService()).keySet())
                .containsExactlyInAnyOrder(PlatformAction.VIEW, PlatformAction.QUERY);
    }

    @Test
    void shouldNotRepublishDisabledCrudActionsFromDefaultWebMethods() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(DisabledCrudWeb.class,
                    () -> new DisabledCrudWeb(new ReadOnlyOperationService()));
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("menu", "query", "view");
        }
    }

    private void assertCustomRecordAction(StaticModuleActionDefinition action, String actionCode, String title) {
        assertThat(action.actionCode()).isEqualTo(actionCode);
        assertThat(action.permissionActionCode()).isEqualTo(actionCode);
        assertThat(action.title()).isEqualTo(title);
        assertThat(action.actionLevel()).isEqualTo(EntityActionLevel.RECORD);
        assertThat(action.accessMode()).isEqualTo(EntityActionAccessMode.AUTH_REQUIRED);
        assertThat(action.actionAuth()).isTrue();
        assertThat(action.dataAuth()).isTrue();
        assertThat(action.defaultGrantPolicy()).isEqualTo(ActionDefaultGrantPolicy.NONE);
    }

    @Test
    void shouldRejectStaticModuleAliasDifferentFromWebScope() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(BadAliasWeb.class);
            context.refresh();
            StaticModuleDefinitionScanner scanner = new StaticModuleDefinitionScanner(context);

            assertThatThrownBy(scanner::scan)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("module-alias web scope");
        }
    }

    @Test
    void shouldRejectNonScopedStaticModuleWithNonCanonicalWebScope() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(NonScopedBadAliasWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("module-alias web scope");
        }
    }

    @Test
    void shouldRejectAdditionalWebScopeWithoutExplicitCustomDeclaration() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(CanonicalAndLegacyScopeWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("module-alias web scope");
        }
    }

    @Test
    void shouldRejectModuleApplicationClassWithoutStaticApplicationDeclaration() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(UndeclaredApplicationWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("application must declare @PlatformStaticApplication")
                    .hasMessageContaining(String.class.getName());
        }
    }

    @Test
    void shouldRejectModuleAliasOutsideDeclaredApplication() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(WrongApplicationOwnerWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("alias must belong to application")
                    .hasMessageContaining("iam.outside is not under platform");
        }
    }

    @Test
    void shouldAllowExplicitCustomWebScope() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(CustomScopeWeb.class);
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo("demo.nested");
        }
    }

    @Test
    void shouldRejectCustomWebScopeWithoutClassLevelMapping() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(CustomScopeWithoutMappingWeb.class);
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@PlatformStaticWebScope(CUSTOM) requires @RequestMapping");
        }
    }

    @Test
    void shouldRejectStaticModuleAliasDifferentFromServiceAlias() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ServiceAliasMismatchWeb.class,
                    () -> new ServiceAliasMismatchWeb(new MultiSegmentModuleService()));
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("alias must match service module alias")
                    .hasMessageContaining("demo.service_alias != platform.workflow.definition");
        }
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class, alias = "iam.bad", title = "Bad")
    @RequestMapping("/iam.good")
    static class BadAliasWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class, alias = "iam.non_scoped_bad", title = "Non scoped bad")
    @RequestMapping("/iam.other")
    static class NonScopedBadAliasWeb {
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class,
            alias = "demo.canonical_and_legacy", title = "Canonical and legacy")
    @RequestMapping({"/demo.canonical_and_legacy", "/demo/canonical-and-legacy"})
    static class CanonicalAndLegacyScopeWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.field_spec", title = "Bad")
    @RequestMapping("/platform.fieldtype")
    static class MissingSeparatorAliasWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
    }

    @RestController
@PlatformStaticModule(application = String.class, alias = "demo.invalid", title = "Invalid")
    static class UndeclaredApplicationWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
            alias = "iam.outside", title = "Wrong owner")
    static class WrongApplicationOwnerWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
    }

    @RestController
    @PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.nested", title = "Nested")
    @RequestMapping("/demo.parent/{parentId}/nested")
    static class CustomScopeWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
    }

    @RestController
    @PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.no_mapping", title = "No mapping")
    static class CustomScopeWithoutMappingWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.service_alias", title = "Service alias")
    @RequestMapping("/demo.service_alias")
    static class ServiceAliasMismatchWeb extends net.ximatai.muyun.spring.web.WebSupport<MultiSegmentModuleService> {
        ServiceAliasMismatchWeb(MultiSegmentModuleService service) {
            this.service = service;
        }
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.SalesApplication.class, alias = "sales.contract", title = "合同",
            capabilities = EntityCapability.APPROVAL)
    static class WorkflowEnabledWeb {
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.dictionary_category", title = "字典管理")
    static class ConflictingDictionaryCategoryWeb {
        @CustomActionEndpoint("item_query")
        public void itemQuery() {
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class,
            alias = "demo.scoped_action", title = "Scoped action")
    static class ScopedActionTargetWeb {
        @ActionEndpoint(PlatformAction.CREATE)
        public void create() {
        }
    }

    @RestController
    @PlatformStaticActionScope(module = "demo.scoped_action")
    static class ScopedCreateActionWeb {
        @ActionEndpoint(PlatformAction.CREATE)
        public void create() {
        }
    }

    @RestController
    @PlatformStaticActionScope(module = "demo.scoped_action")
    static class ScopedCreateActionWithConflictingPolicyWeb {
        @CustomActionEndpoint(value = "create", title = "Scoped create", actionAuth = false)
        public void create() {
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class,
            alias = "demo.scoped_action_without_create", title = "Scoped action without create")
    static class ScopedActionTargetWithoutCreateWeb {
    }

    @RestController
    @PlatformStaticActionScope(module = "demo.scoped_action_without_create")
    static class ScopedCreateActionWithoutTargetActionWeb {
        @ActionEndpoint(PlatformAction.CREATE)
        public void create() {
        }
    }

    @RestController
    @PlatformStaticActionDeclaration(module = "demo.conflicting_action_origin")
    @PlatformStaticActionScope(module = "demo.conflicting_action_origin")
    static class ConflictingActionEndpointOriginsWeb {
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class,
            alias = "demo.contributed_action", title = "Contributed action")
    static class ContributedActionTargetWeb {
    }

    @RestController
    @PlatformStaticActionContribution(targetModule = "demo.contributed_action", resource = "child", resourceTitle = "Child")
    static class FirstContributedActionWeb {
        @CustomActionEndpoint("run")
        public void run() {
        }
    }

    @RestController
    @PlatformStaticActionContribution(targetModule = "demo.contributed_action", resource = "child", resourceTitle = "Child")
    static class SecondContributedActionWeb {
        @CustomActionEndpoint("run")
        public void run() {
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class,
            alias = "demo.conflicting_custom_action", title = "Conflicting custom action")
    static class ConflictingCustomActionDefinitionsWeb {
        @CustomActionEndpoint(value = "run", title = "Run")
        public void run() {
        }

        @CustomActionEndpoint(value = "run", title = "Execute")
        public void execute() {
        }
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.SalesApplication.class, alias = "sales.order_line", title = "订单明细")
    static class StaticMeasureOrderWeb extends net.ximatai.muyun.spring.web.WebSupport<StaticMeasureOrderService> {
        StaticMeasureOrderWeb(StaticMeasureOrderService service) {
            this.service = service;
        }
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.service_ability", title = "Service Ability")
    @RequestMapping("/demo.service_ability")
    static class ServiceDeclaredAbilityWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
        ServiceDeclaredAbilityWeb(Object service) {
            this.service = service;
        }
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.redeclared", title = "Redeclared",
            capabilities = EntityCapability.ENABLE)
    @RequestMapping("/demo.redeclared")
    static class RedeclaredServiceAbilityWeb {
    }

    @DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE})
    static final class ReadOnlyOperationService implements CrudAbility<StandardEntity> {
        @Override
        public BaseDao<StandardEntity, String> getDao() {
            return null;
        }

        @Override
        public String getModuleAlias() {
            return "demo.read_only";
        }
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.read_only", title = "Read only")
    @RequestMapping("/demo.read_only")
    static final class DisabledCrudWeb implements CrudWeb<StandardEntity, ReadOnlyOperationService> {
        private final ReadOnlyOperationService service;

        DisabledCrudWeb(ReadOnlyOperationService service) {
            this.service = service;
        }

        @Override
        public ReadOnlyOperationService service() {
            return service;
        }

        @Override
        public <T> T webScope(java.util.function.Supplier<T> action) {
            return action.get();
        }
    }

    private static class StaticMeasureOrderService extends AbstractAbilityService<StaticMeasureOrderLine> {
        @SuppressWarnings("unchecked")
        StaticMeasureOrderService() {
            super("sales.order_line", StaticMeasureOrderLine.class, mock(BaseDao.class));
        }
    }

    @RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.workflow.definition", title = "流程定义")
    @RequestMapping("/platform.workflow.definition")
    static class MultiSegmentModuleWeb extends net.ximatai.muyun.spring.web.WebSupport<MultiSegmentModuleService> {
        MultiSegmentModuleWeb(MultiSegmentModuleService service) {
            this.service = service;
        }
    }

    private static class MultiSegmentModuleService extends AbstractAbilityService<StaticMeasureOrderLine> {
        @SuppressWarnings("unchecked")
        MultiSegmentModuleService() {
            super("platform.workflow.definition", StaticMeasureOrderLine.class, mock(BaseDao.class));
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class,
            alias = "demo.declared_projection_without_reference", title = "Declared projection without reference")
    static class DeclaredProjectionWithoutReferenceWeb
            extends net.ximatai.muyun.spring.web.WebSupport<DeclaredProjectionWithoutReferenceService> {
        DeclaredProjectionWithoutReferenceWeb(DeclaredProjectionWithoutReferenceService service) {
            this.service = service;
        }
    }

    private static class DeclaredProjectionWithoutReferenceService extends AbstractAbilityService<StaticMeasureOrderLine>
            implements ModuleReadProjectionContributor {
        @SuppressWarnings("unchecked")
        DeclaredProjectionWithoutReferenceService() {
            super("demo.declared_projection_without_reference", StaticMeasureOrderLine.class, mock(BaseDao.class));
        }

        @Override
        public List<ModuleReadProjection> moduleReadProjections() {
            return List.of(ModuleReadProjection.declared("organizationTitle", false, true));
        }
    }

    @Table(name = "sales_order_line", comment = "Sales order line")
    private static class StaticMeasureOrderLine extends StandardEntity {
        @MeasureUnitField(
                categoryAlias = "quantity",
                defaultUnitCode = "box",
                unitFieldName = "quantityUnit",
                baseValueFieldName = "quantityBase",
                baseUnitCode = "bottle",
                conversionMode = MeasureUnitField.ConversionMode.BUSINESS_RULE,
                conversionScopeFieldName = "skuId"
        )
        @Column(name = "quantity", type = ColumnType.NUMERIC, precision = 18, scale = 2)
        private java.math.BigDecimal quantity;

        @Column(name = "quantity_unit", type = ColumnType.VARCHAR, length = 64)
        private String quantityUnit;

        @Column(name = "quantity_base", type = ColumnType.NUMERIC, precision = 18, scale = 2)
        private java.math.BigDecimal quantityBase;

        @Column(name = "sku_id", type = ColumnType.VARCHAR, length = 64)
        private String skuId;
    }
}
