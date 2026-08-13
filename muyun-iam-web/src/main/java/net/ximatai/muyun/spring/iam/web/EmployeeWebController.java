package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.StaticRecordReadProjectionService;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.BusinessMutation;
import net.ximatai.muyun.spring.web.MutationTenantScopeExecutor;
import net.ximatai.muyun.spring.web.MutationTenantScopeResolver;
import net.ximatai.muyun.spring.web.SortWebRequest;
import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegation;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegationService;
import net.ximatai.muyun.spring.iam.employee.EmployeeEmploymentReadService;
import net.ximatai.muyun.spring.iam.employee.EmployeePosition;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
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
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class, alias = "iam.employee", title = "职员管理", route = "/iam/employees")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, title = "职员管理", order = 50)
@RequestMapping("/iam.employee")
public class EmployeeWebController extends WebSupport<EmployeeService> implements
        CrudWeb<Employee, EmployeeService>,
        MutationTenantScopeResolver<Employee>,
        StaticModuleUiContributor {
    private static final ActionExecutionPolicy EMPLOYEE_POSITIONS_POLICY = new ActionExecutionPolicy(
            "employeePositions", PlatformActionLevel.RECORD, ActionAccessMode.AUTH_REQUIRED,
            true, true, ActionDefaultGrantPolicy.NONE, null);
    private final EmployeePositionService employeePositionService;
    private final EmployeeAccountService employeeAccountService;
    private final EmployeeDelegationService employeeDelegationService;
    private OrganizationService organizationService;
    private StaticRecordReadProjectionService staticRecordReadProjectionService;
    private EmployeeEmploymentReadService employeeEmploymentReadService;
    private ActionExecutionPolicyService actionExecutionPolicyService;

    @Autowired
    public EmployeeWebController(EmployeePositionService employeePositionService,
                                 EmployeeAccountService employeeAccountService,
                                 EmployeeDelegationService employeeDelegationService) {
        this.employeePositionService = employeePositionService;
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
    void setEmployeeEmploymentReadService(EmployeeEmploymentReadService employeeEmploymentReadService) {
        this.employeeEmploymentReadService = employeeEmploymentReadService;
    }

    @Autowired
    void setActionExecutionPolicyService(ActionExecutionPolicyService actionExecutionPolicyService) {
        this.actionExecutionPolicyService = actionExecutionPolicyService;
    }

    @Override
    public StaticRecordReadProjectionService staticRecordReadProjectionService() {
        return staticRecordReadProjectionService;
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(EmployeeService.MODULE_ALIAS)
                .listView(list -> list
                        .title("职员列表")
                        .field("employeeNo", field -> field.label("职员编号").width("150px"))
                        .field("organizationTitle", field -> field.label("所属机构").width("160px"))
                        .field("title", field -> field.label("职员姓名").width("150px"))
                        .field("username", field -> field.label("账号").width("150px"))
                        .field("mobile", field -> field.label("手机号").width("150px"))
                        .field("email", field -> field.label("邮箱"))
                        .field("enabled", field -> field.label("状态").uiType("enabledStatus")
                                .width("90px").align("center"))
                        .field("accountBound", field -> field.hidden()))
                .formView(form -> form
                        .title("职员档案")
                        .field("organizationId", field -> field.label("所属机构").required().readOnly())
                        .field("departmentId", field -> field.label("所属部门").required().uiType("recordPicker"))
                        .field("employeeNo", field -> field.label("职员编号").required())
                        .field("title", field -> field.label("职员姓名").required())
                        .field("avatarAssetId", field -> field.label("头像"))
                        .field("gender", field -> field.label("性别"))
                        .field("mobile", field -> field.label("手机号"))
                        .field("email", field -> field.label("邮箱"))
                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus")))
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

    @GetMapping("/{employeeId}/positions")
    @CustomActionEndpoint(value = "employeePositions", title = "职员任岗",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public WebListResponse<EmployeePosition> positions(@PathVariable String employeeId) {
        return employeeRecordScope(employeeId,
                () -> new WebListResponse<>(employeePositionService.positions(employeeId)));
    }

    @GetMapping("/{employeeId}/employment-view")
    @CustomActionEndpoint(value = "employeePositions", title = "职员任岗", level = PlatformActionLevel.RECORD,
            dataAuth = true, recordIdPathVariable = "employeeId")
    public WebListResponse<EmployeeEmploymentReadService.EmployeeEmploymentView> employmentView(@PathVariable String employeeId) {
        return employeeRecordScope(employeeId, () -> employmentViewRecords(employeeId));
    }

    @GetMapping("/recycle-bin/{employeeId}/employment-view")
    @ActionEndpoint(PlatformAction.RECYCLE_BIN_QUERY)
    public WebListResponse<EmployeeEmploymentReadService.EmployeeEmploymentView> recycleBinEmploymentView(
            @PathVariable String employeeId) {
        requireEmployeePositionsAccess(employeeId);
        return employeeRecycleBinScope(employeeId,
                retained -> employmentViewRecords(employeeId, retained));
    }

    @PostMapping("/{employeeId}/positions")
    @CustomActionEndpoint(value = "employeePositions", title = "职员任岗",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public EmployeePosition addPosition(@PathVariable String employeeId,
                                        @RequestBody EmployeePosition relation) {
        return employeeRecordScope(employeeId,
                () -> employeePositionService.select(employeePositionService.addPosition(employeeId, relation)));
    }

    @PostMapping("/{employeeId}/positions/{relationId}/update")
    @CustomActionEndpoint(value = "employeePositions", title = "职员任岗",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public EmployeePosition updatePosition(@PathVariable String employeeId,
                                           @PathVariable String relationId,
                                           @RequestBody EmployeePosition relation) {
        return employeeRecordScope(employeeId, () -> {
            employeePositionService.updatePosition(employeeId, relationId, relation);
            return employeePositionService.select(relationId);
        });
    }

    @PostMapping("/{employeeId}/positions/{relationId}/delete")
    @CustomActionEndpoint(value = "employeePositions", title = "职员任岗",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public int deletePosition(@PathVariable String employeeId,
                                           @PathVariable String relationId) {
        return employeeRecordScope(employeeId,
                () -> employeePositionService.deletePosition(employeeId, relationId));
    }

    @PostMapping("/{employeeId}/positions/{relationId}/enable")
    @CustomActionEndpoint(value = "employeePositions", title = "职员任岗",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public int enablePosition(@PathVariable String employeeId,
                                           @PathVariable String relationId) {
        return employeeRecordScope(employeeId,
                () -> employeePositionService.enablePosition(employeeId, relationId));
    }

    @PostMapping("/{employeeId}/positions/{relationId}/disable")
    @CustomActionEndpoint(value = "employeePositions", title = "职员任岗",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public int disablePosition(@PathVariable String employeeId,
                                            @PathVariable String relationId) {
        return employeeRecordScope(employeeId,
                () -> employeePositionService.disablePosition(employeeId, relationId));
    }

    @PostMapping("/{employeeId}/positions/{relationId}/primary")
    @CustomActionEndpoint(value = "employeePositions", title = "职员任岗",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public int makePrimaryPosition(@PathVariable String employeeId,
                                                @PathVariable String relationId) {
        return employeeRecordScope(employeeId,
                () -> employeePositionService.makePrimaryPosition(employeeId, relationId));
    }

    @PostMapping("/{employeeId}/positions/{relationId}/sort")
    @CustomActionEndpoint(value = "employeePositions", title = "职员任岗",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "employeeId")
    public int sortPosition(@PathVariable String employeeId,
                                         @PathVariable String relationId,
                                         @RequestBody(required = false) SortWebRequest request) {
        return employeeRecordScope(employeeId, () -> {
            SortWebRequest normalized = request == null ? new SortWebRequest(null, null) : request;
            employeePositionService.moveEmployeePosition(employeeId, relationId,
                    normalized.previousId(), normalized.nextId());
            return 1;
        });
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

    private WebListResponse<EmployeeEmploymentReadService.EmployeeEmploymentView> employmentViewRecords(
            String employeeId) {
        if (employeeEmploymentReadService == null) {
            throw new IllegalStateException("employment view is not available");
        }
        return new WebListResponse<>(employeeEmploymentReadService.page(
                new EmployeeEmploymentReadService.Query(employeeId, null, null, Boolean.FALSE,
                        new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE))).getRecords());
    }

    private WebListResponse<EmployeeEmploymentReadService.EmployeeEmploymentView> employmentViewRecords(
            String employeeId, Employee retainedEmployee) {
        if (employeeEmploymentReadService == null) {
            throw new IllegalStateException("employment view is not available");
        }
        return new WebListResponse<>(employeeEmploymentReadService.pageForEmployee(
                retainedEmployee,
                new EmployeeEmploymentReadService.Query(employeeId, null, null, Boolean.FALSE,
                        new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE))).getRecords());
    }

    private <R> R employeeRecycleBinScope(String employeeId, Function<Employee, R> action) {
        if (!service().canAccessRecycleBinRecord(employeeId)) {
            throw new PlatformException("Recycle-bin record is unavailable: " + EmployeeService.MODULE_ALIAS);
        }
        Employee retained = service().selectIgnoreSoftDelete(employeeId);
        Optional<String> tenantId = tenantIdForEmployee(retained);
        if (!TenantContext.isSystem() || tenantId.isEmpty()) {
            return webScope(() -> action.apply(retained));
        }
        try (TenantContext.Scope ignored = TenantContext.use(tenantId.get())) {
            return webScope(() -> action.apply(retained));
        }
    }

    private void requireEmployeePositionsAccess(String employeeId) {
        if (actionExecutionPolicyService == null) {
            throw new IllegalStateException("ActionExecutionPolicyService must be configured");
        }
        actionExecutionPolicyService.requireRecordAction(ActionExecutionContext.ofPolicy(
                EmployeeService.MODULE_ALIAS,
                EMPLOYEE_POSITIONS_POLICY,
                Set.of(employeeId),
                CurrentUserContext.currentUser()));
        if (!service().canAccessRecycleBinRecord(EMPLOYEE_POSITIONS_POLICY, employeeId)) {
            throw new PlatformException("record data permission denied: "
                    + EmployeeService.MODULE_ALIAS + ".employeePositions");
        }
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
