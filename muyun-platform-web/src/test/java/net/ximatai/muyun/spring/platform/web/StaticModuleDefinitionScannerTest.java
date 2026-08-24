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
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
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
import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityFacet;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityDeclarationPolicy;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityModule;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityOperationContext;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityRegistry;
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
            assertThat(definition.uiDefinition().page()).isInstanceOf(FlatManagementPageDefinition.class);
            assertThat(((FlatManagementPageDefinition) definition.uiDefinition().page()).detail().editor())
                    .satisfies(view -> assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("alias"))
                            .singleElement()
                            .satisfies(field -> assertThat(field.readOnly().formula().expression())
                                    .isEqualTo("PRESENT({id})")));
            assertThat(ModuleUiDescriptorCompiler.compile(definition).page()).satisfies(page -> {
                assertThat(page.template()).isEqualTo(ModulePageTemplate.FLAT_MANAGEMENT);
                assertThat(page.explorer().title()).isEqualTo("应用列表");
                assertThat(page.explorer().searchPlaceholder()).isEqualTo("搜索应用名称、alias 或 ID");
                assertThat(page.explorer().emptyDescription()).isEqualTo("暂无应用");
                assertThat(page.detail().emptyDescription()).isEqualTo("请选择应用，或新建应用");
                assertThat(page.detail().createTitle()).isEqualTo("新建应用");
                assertThat(page.explorer().recordLabel()).isEqualTo("应用");
            });
        }
    }

    @Test
    void shouldCompilePlatformModuleAsApplicationScopedTreeManagementPage() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ApplicationWebController.class);
            context.registerBean(PlatformModuleWebController.class,
                    () -> withService(new PlatformModuleWebController(),
                            new PlatformModuleService(mock(BaseDao.class))));
            context.refresh();

            List<StaticModuleDefinition> definitions = new StaticModuleDefinitionScanner(context).scan();
            StaticModuleDefinition definition = definitions.stream()
                    .filter(candidate -> PlatformModuleService.MODULE_ALIAS.equals(candidate.moduleAlias()))
                    .findFirst()
                    .orElseThrow();

            assertThat(definition.navigatorSourceCapabilities()).containsExactlyInAnyOrder(
                    net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability.REFERENCE_QUERY,
                    net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability.REFERENCE_TREE);
            assertThat(definition.uiDefinition().page()).isInstanceOf(TreeManagementPageDefinition.class);

            ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(definition).page();
            assertThat(page.template()).isEqualTo(ModulePageTemplate.TREE_MANAGEMENT);
            assertThat(page.navigator().levels()).extracting(ResolvedPageNavigatorLevelDescriptor::key)
                    .containsExactly("application");
            assertThat(page.navigator().levels()).extracting(ResolvedPageNavigatorLevelDescriptor::kind)
                    .containsExactly(PageNavigatorKind.MICRO_LIST);
            assertThat(page.navigator().contextBindings()).containsExactlyInAnyOrder(
                    new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "application",
                            PageContextTarget.LIST_QUERY, "applicationAlias", null),
                    new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "application",
                            PageContextTarget.FORM_DEFAULT, "applicationAlias", null),
                    new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "application",
                            PageContextTarget.PICKER_QUERY, "applicationAlias", null, "parentId"));
            assertThat(page.detail().display().fields()).extracting(field -> field.fieldRef().fieldName())
                    .containsExactly("applicationAlias", "alias", "title", "parentId", "moduleKind", "entryType",
                            "entryRoute", "entryExternalUrl");
            assertThat(page.detail().display().fields()).filteredOn(field -> field.fieldRef().fieldName()
                    .equals("parentId")).singleElement().satisfies(field -> assertThat(field.treeRootTitle())
                    .isEqualTo("根模块"));
            assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                    .containsExactly("alias", "title", "applicationAlias", "parentId", "moduleKind", "entryType",
                            "entryRoute", "entryExternalUrl", "enabled");
            assertThat(page.detail().editor().fields()).filteredOn(field -> field.fieldRef().fieldName()
                    .equals("moduleKind")).singleElement().satisfies(field -> assertThat(field.option().binding()
                    .sourceType()).isEqualTo("enum"));
            assertThat(page.detail().display().fields()).filteredOn(field -> field.fieldRef().fieldName()
                    .equals("moduleKind")).singleElement().satisfies(field -> assertThat(field.option().inlineItems())
                    .extracting(net.ximatai.muyun.spring.common.option.OptionItem::code)
                    .containsExactly("static", "dynamic"));
            assertThat(page.detail().display().fields()).filteredOn(field -> field.fieldRef().fieldName()
                    .equals("moduleKind")).singleElement().satisfies(field -> assertThat(field.option().inlineItems())
                    .extracting(net.ximatai.muyun.spring.common.option.OptionItem::title)
                    .containsExactly("静态模块", "动态模块"));
            assertThat(page.detail().editor().fields()).filteredOn(field -> field.fieldRef().fieldName()
                    .equals("entryType")).singleElement().satisfies(field -> assertThat(field.option().binding()
                    .sourceType()).isEqualTo("enum"));
            assertThat(page.detail().editor().fields()).filteredOn(field -> field.fieldRef().fieldName()
                    .equals("entryRoute")).singleElement().satisfies(field -> {
                assertThat(field.visible().formula()).isNotNull();
                assertThat(field.visible().formula().expression()).isEqualTo("{entryType} == 'route'");
                assertThat(field.visible().formula().program().profile().name()).isEqualTo("WEB_UI");
                assertThat(field.visible().formula().program().referencedFields()).containsExactly("entryType");
            });
            assertThat(page.detail().editor().fields()).filteredOn(field -> field.fieldRef().fieldName()
                    .equals("entryExternalUrl")).singleElement().satisfies(field -> {
                assertThat(field.visible().formula()).isNotNull();
                assertThat(field.visible().formula().expression()).isEqualTo("{entryType} == 'link'");
                assertThat(field.visible().formula().program().profile().name()).isEqualTo("WEB_UI");
                assertThat(field.visible().formula().program().referencedFields()).containsExactly("entryType");
            });
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
                EmployeeWebController controller = new EmployeeWebController(
                        mock(EmployeeAccountService.class), mock(EmployeeDelegationService.class));
                ReflectionTestUtils.setField(controller, "service", employeeService);
                return controller;
            });
            context.registerBean(PositionWebController.class,
                    () -> withService(new PositionWebController(),
                            new net.ximatai.muyun.spring.iam.position.PositionService(
                                    mock(net.ximatai.muyun.spring.iam.position.PositionDao.class),
                                    mock(net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier.class),
                                    mock(PositionCategoryService.class),
                                    mock(net.ximatai.muyun.spring.iam.employee.EmployeePositionDao.class))));
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
            context.registerBean(PasswordPolicyRuleWebController.class,
                    () -> withService(new PasswordPolicyRuleWebController(), mock(PasswordPolicyRuleService.class)));
            context.refresh();
            StaticModuleDefinitionScanner scanner = new StaticModuleDefinitionScanner(context);

            List<StaticModuleDefinition> definitions = scanner.scan();
            Map<String, StaticModuleDefinition> byAlias = definitions.stream()
                    .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));

            assertThat(byAlias.keySet()).containsExactlyInAnyOrder(
                    "iam.tenant", "iam.organization", "iam.department", "iam.employee",
                    "iam.position_category", "iam.position", "iam.role", "iam.user",
                    "iam.password_policy_rule");
            assertThat(byAlias.get("iam.tenant")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("租户管理");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactly("menu", "create", "view", "update", "delete", "query",
                                "sort", "enable", "disable", "recycleBinQuery", "recycleBinRestore", "reference");
                assertThat(definition.navigatorSourceCapabilities())
                        .containsExactly(net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability.REFERENCE_QUERY);
            });
            assertThat(byAlias.get("iam.organization")).satisfies(definition -> {
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .doesNotContain("recycleBinQuery", "recycleBinRestore");
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("机构管理");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
                assertThat(definition.entryRoute()).isBlank();
                assertThat(definition.navigatorSourceCapabilities()).containsExactlyInAnyOrder(
                        net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability.REFERENCE_QUERY,
                        net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability.REFERENCE_TREE);
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactly("menu", "create", "view", "update", "delete", "query",
                                "tree", "sort", "enable", "disable", "reference");
            });
            assertThat(byAlias.get("iam.position_category").navigatorSourceCapabilities()).containsExactlyInAnyOrder(
                    net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability.REFERENCE_QUERY,
                    net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability.REFERENCE_TREE);
            assertThat(byAlias.get("iam.department")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("部门管理");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
                assertThat(definition.entryRoute()).isBlank();
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                                "tree", "sort", "enable", "disable", "reference");
            });
            assertThat(byAlias.get("iam.employee")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("职员管理");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
                assertThat(definition.entryRoute()).isBlank();
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .contains("menu", "create", "view", "update", "delete", "query",
                                "sort", "enable", "disable", "reference", "employeeAccounts",
                                "employeeDelegations", "employeeDelegatedToMe",
                                "recycleBinQuery", "recycleBinRestore");
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .doesNotContain("recycleBinPurge");
                assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("employeeAccounts"))
                        .singleElement()
                        .satisfies(action -> assertCustomRecordAction(action, "employeeAccounts", "职员账号"));
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
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().list().fields())
                        .satisfies(view -> {
                            assertThat(view.viewKind()).isEqualTo(ModuleViewKind.LIST);
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .containsExactly("employeeNo", "organizationTitle", "title", "username",
                                            "mobile", "email", "enabled", "avatarAssetId", "accountBound");
                        });
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().navigator().levels())
                        .extracting(ResolvedPageNavigatorLevelDescriptor::key)
                        .containsExactly("tenant", "organization");
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().navigator().contextBindings())
                        .contains(new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "organization",
                                PageContextTarget.LIST_QUERY, "organizationId", null));
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().detail().editor())
                        .satisfies(view -> {
                            assertThat(view.viewKind()).isEqualTo(ModuleViewKind.FORM);
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .containsExactly("organizationId", "departmentId", "employeeNo", "title",
                                            "gender", "mobile", "email", "enabled");
                            assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("departmentId"))
                                    .singleElement()
                                    .satisfies(field -> assertThat(field.uiType()).isEqualTo("recordPicker"));
                assertThat(ModuleUiDescriptorCompiler.compile(definition).detailRelations())
                        .extracting(relation -> relation.code())
                        .contains("positions");
                assertThat(ModuleUiDescriptorCompiler.compile(definition).editorContributions())
                        .filteredOn(contribution -> contribution.resource().equals("positions"))
                        .singleElement()
                        .satisfies(contribution -> assertThat(contribution.editor().fields())
                                .filteredOn(field -> field.fieldRef().fieldName().equals("organizationId"))
                                .singleElement()
                                .satisfies(field -> {
                                    assertThat(field.fieldControl().rendererType()).isEqualTo("RECORD_PICKER");
                                    assertThat(field.reference().targetModuleAlias()).isEqualTo("iam.organization");
                                }));
                        });
            });
            assertThat(byAlias.get("iam.position_category")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("岗位分类");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
                assertThat(definition.entryRoute()).isEmpty();
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                                "tree", "sort", "enable", "disable", "reference");
            });
            assertThat(byAlias.get("iam.position")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("岗位管理");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
                assertThat(definition.entryRoute()).isEmpty();
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query",
                                "sort", "enable", "disable", "reference");
                assertThat(definition.uiDefinition()).isNotNull();
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().navigator().levels())
                        .extracting(ResolvedPageNavigatorLevelDescriptor::key)
                        .containsExactly("tenant", "category");
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().navigator().levels())
                        .filteredOn(level -> level.key().equals("category"))
                        .singleElement()
                        .satisfies(level -> assertThat(level.initialSelectionPolicy()).isEqualTo(
                                net.ximatai.muyun.spring.platform.web.PageNavigatorInitialSelectionPolicy.FIRST_RECORD));
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().detail().editor().fields())
                        .extracting(field -> field.fieldRef().fieldName())
                        .containsExactly("categoryId", "code", "title", "description", "enabled");
            });
            assertThat(byAlias.get("iam.role")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.title()).isEqualTo("角色管理");
                assertThat(definition.entryRoute()).isEqualTo("/iam/role");
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
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().list().fields())
                        .satisfies(view -> {
                            assertThat(view.viewKind()).isEqualTo(ModuleViewKind.LIST);
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .containsExactly("title", "assignmentType", "roleKind", "sharePolicy",
                                            "systemManaged", "enabled");
                            assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("enabled"))
                                    .singleElement()
                                    .satisfies(field -> assertThat(field.uiType()).isEqualTo("enabledStatus"));
                        });
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().detail().editor())
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
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
                assertThat(definition.entryRoute()).isEmpty();
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().detail().editor().fields())
                        .filteredOn(field -> field.fieldRef().fieldName().equals("password"))
                        .singleElement()
                        .satisfies(field -> {
                            assertThat(field.required().constant()).isTrue();
                            assertThat(field.uiType()).isEqualTo("password");
                            assertThat(field.fieldControl().alias()).isEqualTo("password");
                        });
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
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page().list().fields())
                        .satisfies(view -> {
                            assertThat(view.fields()).extracting(field -> field.fieldRef().relationCode())
                                    .containsOnlyNulls();
                            assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                    .contains("username", "employeeNo", "employeeTitle")
                                    .doesNotContain("onlineStatus");
                        });
            });
            assertThat(byAlias.get("iam.password_policy_rule")).satisfies(definition -> {
                assertThat(definition.applicationAlias()).isEqualTo("iam");
                assertThat(definition.moduleAlias()).isEqualTo("iam.password_policy_rule");
                assertThat(definition.title()).isEqualTo("密码策略规则");
                assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
                assertThat(definition.uiDefinition().page()).isInstanceOf(FlatManagementPageDefinition.class);
                assertThat(ModuleUiDescriptorCompiler.compile(definition).page()).satisfies(page -> {
                    assertThat(page.template()).isEqualTo(ModulePageTemplate.FLAT_MANAGEMENT);
                    assertThat(page.explorer().title()).isEqualTo("密码规则");
                    assertThat(page.explorer().secondaryField()).isNull();
                    assertThat(page.detail().createTitle()).isEqualTo("新建密码规则");
                    assertThat(page.detail().showSystemInfo()).isFalse();
                    assertThat(page.detail().editor().fields())
                            .extracting(field -> field.fieldRef().fieldName())
                            .containsExactly("title", "pattern", "message", "sortOrder", "description");
                    assertThat(page.detail().display().fields())
                            .extracting(field -> field.fieldRef().fieldName())
                            .contains("scopeTypeTitle");
                });
                assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                        .containsExactly("menu", "create", "view", "update", "delete", "query",
                                "sort", "enable", "disable");
            });
        }
    }

    @Test
    void shouldScanCodeRuleAndQueryViewLifecycleModules() {
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
            assertThat(byAlias.get("platform.menu_scheme").entryRoute()).isEqualTo("/platform/menu-scheme");
            assertThat(byAlias.get("platform.menu").actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("create", "view", "update", "delete", "query",
                            "tree", "sort", "enable", "disable");
            assertThat(byAlias.get("platform.dictionary_category").actions())
                    .extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "query", "reference",
                            "tree", "sort", "enable", "disable",
                            "item_create", "item_view", "item_update", "item_delete", "item_query",
                            "item_tree", "item_sort", "item_enable", "item_disable");
            assertThat(byAlias.get("platform.dictionary_category").entryRoute()).isBlank();
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
            ResolvedModuleUiDescriptor dictionaryDescriptor =
                    ModuleUiDescriptorCompiler.compile(byAlias.get("platform.dictionary_category"));
            assertThat(dictionaryDescriptor.page()).isNotNull();
            assertThat(dictionaryDescriptor.page().template()).isEqualTo(ModulePageTemplate.TREE_MANAGEMENT);
            assertThat(dictionaryDescriptor.page().navigator().levels())
                    .extracting(ResolvedPageNavigatorLevelDescriptor::key,
                            ResolvedPageNavigatorLevelDescriptor::sourceModuleAlias,
                            ResolvedPageNavigatorLevelDescriptor::kind)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("application", "platform.application", PageNavigatorKind.MICRO_LIST),
                            org.assertj.core.groups.Tuple.tuple("category", "platform.dictionary_category", PageNavigatorKind.TREE));
            assertThat(dictionaryDescriptor.page().navigator().levels())
                    .filteredOn(level -> level.key().equals("application"))
                    .singleElement()
                    .satisfies(level -> assertThat(level.management()).isNull());
            assertThat(dictionaryDescriptor.page().navigator().levels())
                    .filteredOn(level -> level.key().equals("category"))
                    .singleElement()
                    .satisfies(level -> assertThat(level.management()).isNotNull());
            assertThat(dictionaryDescriptor.page().treeResource()).isNotNull().satisfies(resource -> {
                assertThat(resource.resource()).isEqualTo("item");
                assertThat(resource.scopeNavigatorKey()).isEqualTo("category");
                assertThat(resource.scopeField()).isEqualTo("categoryId");
                assertThat(resource.title()).isEqualTo("字典项");
            });
            assertThat(dictionaryDescriptor.page().detail().editor()).satisfies(view -> {
                assertThat(view.viewKind()).isEqualTo(ModuleViewKind.FORM);
                assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                        .containsExactly("applicationAlias", "parentId", "alias", "categoryKind", "title", "enabled");
                assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("categoryKind"))
                        .singleElement()
                        .satisfies(field -> {
                            assertThat(field.uiType()).isEqualTo("select");
                            assertThat(field.option()).isNotNull();
                        });
            });
            assertThat(ModuleUiDescriptorCompiler.compile(byAlias.get("platform.dictionary_category"))
                    .editorContributions())
                    .singleElement()
                    .satisfies(contribution -> {
                        assertThat(contribution.resource()).isEqualTo("item");
                        ResolvedViewDescriptor view = contribution.editor();
                        assertThat(view.viewKind()).isEqualTo(ModuleViewKind.FORM);
                        assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                                .containsExactly("categoryId", "code", "title", "parentId", "enabled");
                        assertThat(view.fields()).extracting(field -> field.fieldRef().relationCode())
                                .containsOnly("item");
                        assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("parentId"))
                                .singleElement()
                                .satisfies(field -> {
                                    assertThat(field.uiType()).isEqualTo("recordPicker");
                                    assertThat(field.treeRootTitle()).isEqualTo("根字典项");
                                });
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
    void shouldAllowAnnotationOwnedCapabilitiesAndAssembleWorkflowActions() {
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
            assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .containsExactly("menu", "create", "view", "update", "delete", "query",
                            "sort", "enable", "disable", "reference");
            assertThat(definition.uiDefinition()).isNotNull();
            assertThat(((FlatManagementPageDefinition) definition.uiDefinition().page()).detail().editor())
                    .satisfies(view -> {
                assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                        .containsExactly("alias", "title", "fieldType", "defaultLength", "defaultPrecision",
                                "defaultScale", "defaultQueryOperator", "queryOperators", "defaultUiControlAlias",
                                "uiControlAliases", "enabled");
                assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("alias"))
                        .singleElement().satisfies(field -> assertThat(field.readOnly().formula().expression())
                                .isEqualTo("PRESENT({id})"));
            });
            assertThat(ModuleUiDescriptorCompiler.compile(definition).page()).satisfies(page -> {
                assertThat(page.template()).isEqualTo(ModulePageTemplate.FLAT_MANAGEMENT);
                assertThat(page.explorer().title()).isEqualTo("字段规格列表");
                assertThat(page.explorer().emptyDescription()).isEqualTo("暂无字段规格");
                assertThat(page.explorer().recordLabel()).isEqualTo("字段规格");
            });
            assertThat(ModuleUiDescriptorCompiler.compile(definition).page().detail().editor())
                    .satisfies(view -> {
                        assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("fieldType"))
                                .singleElement().satisfies(field -> assertThat(field.option().binding().sourceType())
                                        .isEqualTo("enum"));
                        assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("queryOperators"))
                                .singleElement().satisfies(field -> assertThat(field.option().selectionMode().name())
                                        .isEqualTo("MULTIPLE"));
                        assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("uiControlAliases"))
                                .singleElement().satisfies(field -> assertThat(field.reference().cardinality().name())
                                        .isEqualTo("MANY"));
                    });
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
            context.registerBean(FieldUiControlWebController.class,
                    () -> {
                        var service = mock(net.ximatai.muyun.spring.platform.metadata.FieldUiControlService.class);
                        org.mockito.Mockito.when(service.modelClass()).thenReturn(net.ximatai.muyun.spring.platform.metadata.FieldUiControl.class);
                        return withService(new FieldUiControlWebController(), service);
                    });
            context.registerBean(FieldUiControlPropertyWebController.class,
                    () -> {
                        var service = mock(net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService.class);
                        org.mockito.Mockito.when(service.modelClass()).thenReturn(net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty.class);
                        return withService(new FieldUiControlPropertyWebController(), service);
                    });
            context.registerBean(FieldUiControlBindingWebController.class,
                    () -> {
                        var service = mock(net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService.class);
                        org.mockito.Mockito.when(service.modelClass()).thenReturn(net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding.class);
                        return withService(new FieldUiControlBindingWebController(), service);
                    });
            context.refresh();

            Map<String, StaticModuleDefinition> byAlias = new StaticModuleDefinitionScanner(context).scan().stream()
                    .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));

            assertThat(byAlias).containsOnlyKeys("platform.field_ui_control");
            assertThat(byAlias.get("platform.field_ui_control").entities())
                    .extracting(net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition::alias)
                    .contains("field_ui_control", "field_ui_control_property", "field_ui_control_binding");
            assertThat(byAlias.get("platform.field_ui_control").uiDefinition().detailRelations())
                    .allSatisfy(relation -> {
                        assertThat(relation.embedded()).isTrue();
                        assertThat(relation.managedQuery()).isFalse();
                        assertThat(relation.mutation()).isNull();
                    });

            ResolvedModuleUiDescriptor resolvedFieldControl = ModuleUiDescriptorCompiler
                    .compileModule(byAlias.get("platform.field_ui_control")).uiDescriptor();
            assertThat(resolvedFieldControl.detailRelations())
                    .allSatisfy(relation -> {
                        assertThat(relation.editing().mode())
                                .isEqualTo(net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationEditing.Mode.INLINE);
                        assertThat(relation.editing().saveMode())
                                .isEqualTo(net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationEditing.SaveMode.AGGREGATE_DRAFT);
                        assertThat(relation.editing().recycleBinEnabled()).isTrue();
                    });
            assertThat(resolvedFieldControl.detailRelations())
                    .filteredOn(relation -> relation.code().equals("properties"))
                    .singleElement()
                    .satisfies(relation -> {
                        assertThat(relation.embeddedField()).isEqualTo("properties");
                        assertThat(relation.queryContract()).isNull();
                        assertThat(relation.visible().constant()).isTrue();
                        assertThat(relation.listProjection().fields())
                            .extracting(field -> Map.entry(field.fieldName(), field.width()))
                            .containsExactly(
                                    Map.entry("attributeAlias", 180),
                                    Map.entry("title", 220),
                                    Map.entry("valueFieldSpecAlias", 180),
                                    Map.entry("defaultValue", 240));
                    });
            assertThat(resolvedFieldControl.detailRelations())
                    .filteredOn(relation -> relation.code().equals("bindings"))
                    .singleElement()
                    .satisfies(relation -> {
                        assertThat(relation.embeddedField()).isEqualTo("bindings");
                        assertThat(relation.queryContract()).isNull();
                        assertThat(relation.visible().formula().expression())
                                .isEqualTo("{valueShape} == 'COMPOSITE'");
                        assertThat(relation.visible().formula().program()).isNotNull();
                        assertThat(relation.listProjection().fields())
                            .extracting(field -> Map.entry(field.fieldName(), field.width()))
                            .containsExactly(
                                    Map.entry("valueKey", 220),
                                    Map.entry("valueFieldSpecAlias", 220),
                                    Map.entry("title", 360));
                    });
            assertThat(resolvedFieldControl.editorContributions())
                    .flatExtracting(contribution -> contribution.editor().fields())
                    .filteredOn(field -> field.fieldRef().fieldName().equals("valueFieldSpecAlias"))
                    .allSatisfy(field -> {
                        assertThat(field.uiType()).isEqualTo("recordPicker");
                        assertThat(field.reference()).isNotNull();
                        assertThat(field.reference().targetModuleAlias()).isEqualTo(FieldSpecService.MODULE_ALIAS);
                    });

            Map<String, ResolvedViewFieldDescriptor> editorFields = ModuleUiDescriptorCompiler
                    .compileModule(byAlias.get("platform.field_ui_control"))
                    .uiDescriptor().page().detail().editor().fields().stream()
                    .collect(Collectors.toMap(field -> field.fieldRef().fieldName(), Function.identity()));
            assertThat(editorFields.get("defaultFieldSpecAlias")).satisfies(field -> {
                assertThat(field.uiType()).isEqualTo("recordPicker");
                assertThat(field.reference()).isNotNull();
            });
            assertThat(editorFields.get("primaryValueKey")).satisfies(field -> {
                assertThat(field.visible().formula().expression()).isEqualTo("{valueShape} == 'COMPOSITE'");
                assertThat(field.required().formula().expression()).isEqualTo("{valueShape} == 'COMPOSITE'");
            });
            assertThat(editorFields).extractingByKeys("rendererType", "valueShape", "queryMode")
                    .allSatisfy(field -> {
                        assertThat(field.uiType()).isEqualTo("select");
                        assertThat(field.option()).isNotNull();
                        assertThat(field.option().inlineItems()).isNotEmpty();
                    });
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
        Object service = mock(CrudAbility.class, withSettings().extraInterfaces(EnableAbility.class));
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RedeclaredServiceAbilityWeb.class,
                    () -> new RedeclaredServiceAbilityWeb(service));
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not redeclare service ability")
                    .hasMessageContaining("demo.redeclared.ENABLE");
        }
    }

    @Test
    void shouldRejectServiceOnlyCapabilitiesDeclaredByPlainServiceAnnotation() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PlainServiceOnlyCapabilityDeclarationWeb.class,
                    () -> new PlainServiceOnlyCapabilityDeclarationWeb(new Object()));
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not redeclare service ability")
                    .hasMessageContaining("demo.plain_declared.CRUD");
        }
    }

    @Test
    void shouldRejectRegisteredServiceOnlyCapabilityDeclaredByPlainServiceAnnotation() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PlainEnableCapabilityDeclarationWeb.class,
                    () -> new PlainEnableCapabilityDeclarationWeb(new Object()));
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not redeclare service ability")
                    .hasMessageContaining("demo.plain_enable.ENABLE");
        }
    }

    @Test
    void shouldCompileRegisteredStaticFacetWithoutChangingScannerBranches() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(FacetOnlyWeb.class, () -> new FacetOnlyWeb(new FacetOnlyService()));
            context.refresh();

            StaticCapabilityRegistry registry = () -> List.of(new ApprovalStaticFacetModule());
            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context, registry).scan().getFirst();

            assertThat(definition.capabilities()).contains(EntityCapability.APPROVAL);
            assertThat(definition.actions()).extracting(StaticModuleActionDefinition::actionCode)
                    .contains("export");
        }
    }

    @Test
    void shouldRejectStaticFacetWhenItsServiceMissesDeclaredDependency() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(FacetOnlyWeb.class, () -> new FacetOnlyWeb(new FacetOnlyService()));
            context.refresh();

            StaticCapabilityRegistry registry = () -> List.of(new MissingCrudApprovalStaticFacetModule());

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context, registry).scan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APPROVAL requires CRUD");
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

    @Test
    void shouldRejectUnknownStaticUiFieldWhileScanningInsteadOfAtFirstPageRequest() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(InvalidStaticUiWeb.class,
                    () -> new InvalidStaticUiWeb(new InvalidStaticUiService()));
            context.refresh();

            assertThatThrownBy(() -> new StaticModuleDefinitionScanner(context).scan())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("static module UI field is not declared by model facts")
                    .hasMessageContaining("sales.invalid_static_ui.default_list.missingField");
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
    static class RedeclaredServiceAbilityWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
        RedeclaredServiceAbilityWeb(Object service) {
            this.service = service;
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class,
            alias = "demo.plain_declared", title = "Plain declared",
            capabilities = {EntityCapability.CRUD, EntityCapability.ENABLE})
    static class PlainServiceOnlyCapabilityDeclarationWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
        PlainServiceOnlyCapabilityDeclarationWeb(Object service) {
            this.service = service;
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class,
            alias = "demo.plain_enable", title = "Plain enable", capabilities = EntityCapability.ENABLE)
    static class PlainEnableCapabilityDeclarationWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
        PlainEnableCapabilityDeclarationWeb(Object service) {
            this.service = service;
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class,
            alias = "demo.facet_only", title = "Facet only")
    static class FacetOnlyWeb extends net.ximatai.muyun.spring.web.WebSupport<Object> {
        FacetOnlyWeb(Object service) {
            this.service = service;
        }
    }

    private static final class FacetOnlyService {
    }

    private static class ApprovalStaticFacetModule implements StaticCapabilityModule {
        @Override public EntityCapability capability() { return EntityCapability.APPROVAL; }
        @Override public StaticCapabilityDeclarationPolicy declarationPolicy() {
            return StaticCapabilityDeclarationPolicy.ANNOTATION_OWNED;
        }
        @Override public java.util.Optional<StaticCapabilityFacet> staticFacet() {
            return java.util.Optional.of(new StaticCapabilityFacet() {
                @Override public boolean supports(Object service) { return service instanceof FacetOnlyService; }
                @Override public List<PlatformOperationDefinition> standardOperations(
                        StaticCapabilityOperationContext context) {
                    return List.of(new PlatformOperationDefinition("approval", "export", PlatformAction.EXPORT));
                }
            });
        }
    }

    private static final class MissingCrudApprovalStaticFacetModule extends ApprovalStaticFacetModule {
        @Override public java.util.Set<EntityCapability> dependencies() {
            return java.util.Set.of(EntityCapability.CRUD);
        }
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

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.SalesApplication.class,
            alias = "sales.invalid_static_ui", title = "Invalid static UI")
    @RequestMapping("/sales.invalid_static_ui")
    static class InvalidStaticUiWeb extends net.ximatai.muyun.spring.web.WebSupport<InvalidStaticUiService>
            implements StaticModuleUiContributor {
        InvalidStaticUiWeb(InvalidStaticUiService service) {
            this.service = service;
        }

        @Override
        public ModuleUiDefinition moduleUiDefinition() {
            return ModuleUiDefinition.builder("sales.invalid_static_ui")
                    .page(PageTemplates.listDetailCard(page -> page
                            .list(list -> list.fields(fields -> fields.field(ModuleUiField.of("missingField"))))
                            .detail(detail -> detail.editor(form -> form.field(ModuleUiField.of("quantity"))))))
                    .build();
        }
    }

    private static class InvalidStaticUiService extends AbstractAbilityService<StaticMeasureOrderLine> {
        @SuppressWarnings("unchecked")
        InvalidStaticUiService() {
            super("sales.invalid_static_ui", StaticMeasureOrderLine.class, mock(BaseDao.class));
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
