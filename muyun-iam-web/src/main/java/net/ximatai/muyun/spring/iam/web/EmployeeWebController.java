package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.PageNavigatorSingleResultPolicy;
import net.ximatai.muyun.spring.platform.web.PageNavigatorSourceScope;
import net.ximatai.muyun.spring.platform.web.UiRule;
import net.ximatai.muyun.spring.platform.web.StaticRecordReadProjectionService;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.LegacyStaticReadProjectionCompatibility;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.AggregateChildRelationExpansionGateway;
import net.ximatai.muyun.spring.platform.web.AggregateChildRelationExpansionWeb;
import net.ximatai.muyun.spring.web.BusinessMutation;
import net.ximatai.muyun.spring.web.MutationTenantScopeExecutor;
import net.ximatai.muyun.spring.web.MutationTenantScopeResolver;
import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeFormulas;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegation;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegationService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class, alias = "iam.employee", title = "职员管理")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, title = "职员管理", order = 50)
@RequestMapping("/iam.employee")
public class EmployeeWebController extends WebSupport<EmployeeService> implements
        CrudWeb<Employee, EmployeeService>,
        AggregateChildRelationExpansionWeb<Employee, EmployeeService>,
        MutationTenantScopeResolver<Employee>,
        StaticModuleUiContributor,
        LegacyStaticReadProjectionCompatibility {
    private final EmployeeAccountService employeeAccountService;
    private final EmployeeDelegationService employeeDelegationService;
    private OrganizationService organizationService;
    private StaticRecordReadProjectionService staticRecordReadProjectionService;
    private AggregateChildRelationExpansionGateway aggregateChildRelationExpansionGateway;

    @Autowired
    public EmployeeWebController(EmployeeAccountService employeeAccountService,
                                 EmployeeDelegationService employeeDelegationService) {
        this.employeeAccountService = employeeAccountService;
        this.employeeDelegationService = employeeDelegationService;
    }

    @Autowired
    void setOrganizationService(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @Autowired(required = false)
    void setStaticRecordReadProjectionService(StaticRecordReadProjectionService staticRecordReadProjectionService) {
        this.staticRecordReadProjectionService = staticRecordReadProjectionService;
    }

    @Autowired(required = false)
    void setAggregateChildRelationExpansionGateway(
            AggregateChildRelationExpansionGateway aggregateChildRelationExpansionGateway) {
        this.aggregateChildRelationExpansionGateway = aggregateChildRelationExpansionGateway;
    }

    @Override
    public AggregateChildRelationExpansionGateway aggregateChildRelationExpansionGateway() {
        return aggregateChildRelationExpansionGateway;
    }

    @Override
    public StaticRecordReadProjectionService staticRecordReadProjectionService() {
        return staticRecordReadProjectionService;
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(EmployeeService.MODULE_ALIAS)
                .page(PageTemplates.listDetailCard(page -> page
                .navigator(navigator -> navigator
                        .level("tenant", level -> level
                                .microList("iam.tenant", "租户", "搜索租户")
                                .sourceScope(PageNavigatorSourceScope.CURRENT_TENANT)
                                .singleResultPolicy(PageNavigatorSingleResultPolicy.AUTO_SELECT_AND_HIDE))
                        .level("organization", level -> level
                                .tree(OrganizationService.MODULE_ALIAS, "机构树", "搜索机构"))
                        .bindNavigatorToNavigator("tenant", "organization", "tenantId")
                        .bindNavigatorToList("organization", "organizationId"))
                .list(list -> list.fields(fields -> fields
                        .title("职员列表")
                        .field("employeeNo", field -> field.label("职员编号").width("150px"))
                        .field("organizationTitle", field -> field.label("所属机构").width("160px"))
                        .field("title", field -> field.label("职员姓名").width("150px"))
                        .field("username", field -> field.label("账号").width("150px"))
                        .field("mobile", field -> field.label("手机号").width("150px"))
                        .field("email", field -> field.label("邮箱").width("180px"))
                        .field("enabled", field -> field.label("状态").uiType("enabledStatus")
                                .width("90px").align("center"))
                        // Declares the self-service avatar reference for the shared file-transfer runtime
                        // without exposing it to organization administrators in the employee management UI.
                        .field("avatarAssetId", field -> field.hidden())
                        .field("accountBound", field -> field.hidden()))
                        .expandRelation("positions", expansion -> expansion.columns(
                                "organizationId", "departmentId", "positionId", "primaryPosition", "enabled")))
                .detail(detail -> detail.editor(form -> form
                        .title("职员档案")
                        .field("organizationId", field -> field.label("所属机构").required().readOnly())
                        .field("departmentId", field -> field.label("所属部门").required().uiType("recordPicker"))
                        .field("employeeNo", field -> field.label("职员编号").required())
                        .field("title", field -> field.label("职员姓名").required())
                        .field("gender", field -> field.label("性别"))
                        .field("mobile", field -> field.label("手机号"))
                        .field("email", field -> field.label("邮箱"))
                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))))
                .traits(traits -> traits.standardCrud().enabledStatus().recycleBin().responsiveDetailSurface())))
                .editorContribution("positions", form -> form.title("任职")
                        .field("positions", "organizationId", field -> field.label("所属机构")
                                .width("180px").uiType("recordPicker").required())
                        .field("positions", "departmentId", field -> field.label("所属部门")
                                .width("180px").uiType("recordPicker").required())
                        .field("positions", "positionId", field -> field.label("岗位")
                                .width("180px").uiType("recordPicker").required())
                        .field("positions", "primaryPosition", field -> field.label("主岗位")
                                .width("100px"))
                        .field("positions", "enabled", field -> field.label("启用状态")
                                .width("110px").uiType("enabledStatus")))
                .aggregateChildRelation("positions", "任职", "positions", "employeeId",
                        UiRule.constant(Boolean.TRUE), true, List.of(EmployeeFormulas.primaryPositionExclusive()))
                .build();
    }

    @Override
    public Optional<String> tenantIdForCreate(Employee record) {
        return tenantIdForEmployee(record);
    }

    @Override
    public Optional<String> tenantIdForUpdate(String id, Employee record) {
        Employee existing = service().select(id);
        if (existing != null) {
            return tenantIdForEmployee(existing);
        }
        return tenantIdForCreate(record);
    }

    @Override
    public Optional<String> tenantIdForExistingRecord(String id) {
        return tenantIdForEmployee(service().select(id));
    }

    @GetMapping("/{employeeId}/account")
    @CustomActionEndpoint(value = "employeeAccounts", title = "职员账号",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public EmployeeAccount account(@PathVariable String employeeId) {
        return employeeRecordScope(employeeId, () -> employeeAccountService.accountOfEmployee(employeeId));
    }

    @PostMapping("/{employeeId}/account")
    @CustomActionEndpoint(value = "employeeAccounts", title = "职员账号",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public EmployeeAccount bindAccount(@PathVariable String employeeId,
                                       @RequestBody EmployeeAccount binding) {
        return employeeRecordScope(employeeId,
                () -> employeeAccountService.select(employeeAccountService.bindAccount(employeeId, binding)));
    }

    @PostMapping("/{employeeId}/account/provision")
    @BusinessMutation
    @CustomActionEndpoint(value = "employeeAccounts", title = "职员账号",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public AccountProvisionResponse provisionAccount(@PathVariable String employeeId,
                                                     @RequestBody UserAccount account) {
        return employeeRecordScope(employeeId, () -> {
            EmployeeAccountService.AccountProvisionResult result =
                    employeeAccountService.provisionAccount(employeeId, account);
            return new AccountProvisionResponse(result.user(), result.binding());
        });
    }

    @PostMapping("/{employeeId}/account/delete")
    @BusinessMutation
    @CustomActionEndpoint(value = "employeeAccounts", title = "职员账号",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public int deleteAccount(@PathVariable String employeeId) {
        return employeeRecordScope(employeeId,
                () -> employeeAccountService.removeAccount(employeeId));
    }

    @GetMapping("/{employeeId}/delegations")
    @CustomActionEndpoint(value = "employeeDelegations", title = "职员业务代办",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public WebListResponse<EmployeeDelegation> delegations(@PathVariable String employeeId) {
        return employeeRecordScope(employeeId,
                () -> new WebListResponse<>(employeeDelegationService.delegationsByPrincipal(employeeId)));
    }

    @GetMapping("/{employeeId}/delegated-to-me")
    @CustomActionEndpoint(value = "employeeDelegatedToMe", title = "职员受托代办",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public WebListResponse<EmployeeDelegation> delegatedToMe(@PathVariable String employeeId) {
        return employeeRecordScope(employeeId,
                () -> new WebListResponse<>(employeeDelegationService.delegationsByDelegate(employeeId)));
    }

    @PostMapping("/{employeeId}/delegations")
    @CustomActionEndpoint(value = "employeeDelegations", title = "职员业务代办",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public EmployeeDelegation addDelegation(@PathVariable String employeeId,
                                            @RequestBody EmployeeDelegation delegation) {
        return employeeRecordScope(employeeId, () -> employeeDelegationService.select(
                employeeDelegationService.addDelegation(employeeId, delegation)));
    }

    @PostMapping("/{employeeId}/delegations/{delegationId}/update")
    @CustomActionEndpoint(value = "employeeDelegations", title = "职员业务代办",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public EmployeeDelegation updateDelegation(@PathVariable String employeeId,
                                               @PathVariable String delegationId,
                                               @RequestBody EmployeeDelegation delegation) {
        return employeeRecordScope(employeeId, () -> {
            employeeDelegationService.updateDelegation(employeeId, delegationId, delegation);
            return employeeDelegationService.select(delegationId);
        });
    }

    @PostMapping("/{employeeId}/delegations/{delegationId}/delete")
    @CustomActionEndpoint(value = "employeeDelegations", title = "职员业务代办",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public int deleteDelegation(@PathVariable String employeeId,
                                             @PathVariable String delegationId) {
        return employeeRecordScope(employeeId, () ->
                employeeDelegationService.deleteDelegation(employeeId, delegationId));
    }

    @PostMapping("/{employeeId}/delegations/{delegationId}/enable")
    @CustomActionEndpoint(value = "employeeDelegations", title = "职员业务代办",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public int enableDelegation(@PathVariable String employeeId,
                                             @PathVariable String delegationId) {
        return employeeRecordScope(employeeId, () ->
                employeeDelegationService.enableDelegation(employeeId, delegationId));
    }

    @PostMapping("/{employeeId}/delegations/{delegationId}/disable")
    @CustomActionEndpoint(value = "employeeDelegations", title = "职员业务代办",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public int disableDelegation(@PathVariable String employeeId,
                                              @PathVariable String delegationId) {
        return employeeRecordScope(employeeId, () ->
                employeeDelegationService.disableDelegation(employeeId, delegationId));
    }

    private <R> R employeeRecordScope(String employeeId, Supplier<R> action) {
        return MutationTenantScopeExecutor.forExistingRecord(this, employeeId, () -> webScope(action));
    }

    public record AccountProvisionResponse(UserAccount user, EmployeeAccount binding) {
    }

    private Optional<String> tenantIdForEmployee(Employee employee) {
        if (employee == null) {
            return Optional.empty();
        }
        if (employee.getTenantId() != null && !employee.getTenantId().isBlank()) {
            return Optional.of(employee.getTenantId().trim());
        }
        String organizationId = employee.getOrganizationId();
        if (organizationId == null || organizationId.isBlank()) {
            return Optional.empty();
        }
        Organization organization = organizationService.requireEnabled(organizationId,
                "organization is not active: " + organizationId);
        return Optional.of(net.ximatai.muyun.spring.common.util.Preconditions.requireText(
                organization.getTenantId(), "organization.tenantId"));
    }
}
