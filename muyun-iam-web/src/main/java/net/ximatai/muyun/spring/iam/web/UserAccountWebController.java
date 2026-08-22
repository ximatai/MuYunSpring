package net.ximatai.muyun.spring.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.web.BusinessMutationChange;
import net.ximatai.muyun.spring.web.BusinessMutationRecordIdSource;
import net.ximatai.muyun.spring.web.BusinessMutationResult;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.MutationTenantScopeExecutor;
import net.ximatai.muyun.spring.web.MutationTenantScopeResolver;
import net.ximatai.muyun.spring.web.WebOutputSupport;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.StaticRecordReadProjectionService;
import net.ximatai.muyun.spring.platform.web.StandardModuleWebRuntime;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.iam.user.UserSessionStatusView;
import net.ximatai.muyun.spring.iam.user.UserSessionView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = "iam.user", title = "用户管理", route = "/iam/user")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, order = 60)
@RequestMapping("/iam.user")
public class UserAccountWebController extends WebSupport<UserAccountService> implements
        CrudWeb<UserAccount, UserAccountService>,
        MutationTenantScopeResolver<UserAccount>,
        StaticModuleUiContributor {
    private static final ActionExecutionPolicy USER_SELECTOR_POLICY = new ActionExecutionPolicy(
            "userSelector",
            PlatformActionLevel.LIST,
            ActionAccessMode.AUTH_REQUIRED,
            true,
            true,
            ActionDefaultGrantPolicy.NONE,
            null
    );
    private static final List<String> USER_SELECTOR_OUTPUT_FIELDS = List.of(
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

    private final RoleService roleService;
    private final EmployeeAccountService employeeAccountService;
    private final EmployeeService employeeService;
    private final UserSessionService userSessionService;
    private StaticRecordReadProjectionService staticRecordReadProjectionService;
    private StandardModuleWebRuntime standardModuleWebRuntime;

    public UserAccountWebController() {
        this(null, null, null, null);
    }

    public UserAccountWebController(ObjectProvider<RoleService> roleService) {
        this(roleService, null, null, null);
    }

    @Autowired
    public UserAccountWebController(ObjectProvider<RoleService> roleService,
                                    ObjectProvider<EmployeeAccountService> employeeAccountService,
                                    ObjectProvider<EmployeeService> employeeService,
                                    ObjectProvider<UserSessionService> userSessionService) {
        this.roleService = roleService == null ? null : roleService.getIfAvailable();
        this.employeeAccountService = employeeAccountService == null ? null : employeeAccountService.getIfAvailable();
        this.employeeService = employeeService == null ? null : employeeService.getIfAvailable();
        this.userSessionService = userSessionService == null ? null : userSessionService.getIfAvailable();
    }

    @Autowired(required = false)
    void setStaticRecordReadProjectionService(StaticRecordReadProjectionService staticRecordReadProjectionService) {
        this.staticRecordReadProjectionService = staticRecordReadProjectionService;
    }

    @Autowired(required = false)
    void setStandardModuleWebRuntime(StandardModuleWebRuntime standardModuleWebRuntime) {
        this.standardModuleWebRuntime = standardModuleWebRuntime;
    }

    @Override
    public StaticRecordReadProjectionService staticRecordReadProjectionService() {
        return staticRecordReadProjectionService;
    }

    @Override
    public StandardModuleWebRuntime standardModuleWebRuntime() {
        return standardModuleWebRuntime;
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(UserAccountService.MODULE_ALIAS)
                .page(PageTemplates.listDetailCard(page -> page
                .list(list -> list.fields(fields -> fields
                        .title("用户列表")
                        .field("username", field -> field.label("账号").width("180px"))
                        .field("enabled", field -> field.label("状态").uiType("enabledStatus")
                                .width("90px").align("center"))
                        .field("passwordStatus", field -> field.label("密码状态").width("120px"))
                        .field("employeeNo", field -> field.label("职员工号").width("150px"))
                        .field("employeeTitle", field -> field.label("职员姓名").width("150px"))
                        .field("lastLoginAt", field -> field.label("最后登录时间").width("180px"))))
                .detail(detail -> detail.editor(form -> form
                        .title("用户账号")
                        .field("username", field -> field.label("账号").required())
                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))
                        .field("passwordStatus", field -> field.label("密码状态").readOnly())
                        .field("lastLoginAt", field -> field.label("最后登录时间").readOnly())))
                .traits(traits -> traits.standardCrud().enabledStatus().responsiveDetailSurface())))
                .build();
    }

    @Override
    @GetMapping("/view/{id}")
    @ActionEndpoint(PlatformAction.VIEW)
    public UserAccount view(@PathVariable String id) {
        return webScope(() -> WebOutputSupport.record(service(),
                service().selectForView(id), FieldOutputContext.VIEW));
    }

    @PostMapping("/changePassword/{id}")
    @CustomActionEndpoint(value = "changePassword", title = "修改密码",
            level = PlatformActionLevel.RECORD, dataAuth = true)
    @BusinessMutationResult(code = "iam.user.password-changed", message = "密码已修改",
            change = BusinessMutationChange.UPDATED, module = UserAccountService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public int changePassword(@PathVariable String id,
                                           @RequestBody ChangePasswordRequest request) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            return service().changePassword(id, request.password());
        }));
    }

    @PostMapping("/resetPassword/{id}")
    @CustomActionEndpoint(value = "resetPassword", title = "重置密码",
            level = PlatformActionLevel.RECORD, dataAuth = true)
    @BusinessMutationResult(code = "iam.user.password-reset", message = "密码已重置",
            change = BusinessMutationChange.UPDATED, module = UserAccountService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public ResetPasswordResponse resetPassword(@PathVariable String id) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            UserAccountService.PasswordResetResult result = service().resetPassword(id);
            return new ResetPasswordResponse(result.count(), result.temporaryPassword(), result.expiresAt());
        }));
    }

    @PostMapping("/forceLogout/{id}")
    @CustomActionEndpoint(value = "forceLogout", title = "强制下线",
            level = PlatformActionLevel.RECORD, dataAuth = true)
    @BusinessMutationResult(code = "iam.user.force-logout", message = "用户已下线",
            change = BusinessMutationChange.UPDATED, module = UserAccountService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public int forceLogout(@PathVariable String id) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            return service().forceLogout(id);
        }));
    }

    @GetMapping("/{id}/sessions")
    @CustomActionEndpoint(value = "sessions", title = "在线会话",
            level = PlatformActionLevel.RECORD, dataAuth = true)
    public List<UserSessionView> activeSessions(@PathVariable String id, HttpServletRequest request) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            if (userSessionService == null) {
                return List.of();
            }
            return userSessionService.activeSessionsOfUser(id, bearerToken(request));
        }));
    }

    @PostMapping("/sessions/status")
    @CustomActionEndpoint(value = "sessionStatuses", title = "在线状态", level = PlatformActionLevel.LIST,
            dataAuth = true)
    public List<UserSessionStatusView> sessionStatuses(@RequestBody(required = false) SessionStatusRequest request) {
        return webScope(() -> {
            if (userSessionService == null || request == null) {
                return List.of();
            }
            List<String> userIds = request.userIds() == null ? List.of() : request.userIds().stream()
                    .filter(userId -> userId != null && !userId.isBlank())
                    .distinct()
                    .toList();
            if (userIds.isEmpty()) {
                return List.of();
            }
            requireReadableUsers(userIds);
            return userSessionService.activeSessionStatuses(userIds);
        });
    }

    private void requireReadableUsers(List<String> userIds) {
        DataScopeAbility<UserAccount> dataScope = DataScopeAbility.cast(service());
        List<UserAccount> visibleUsers = dataScope.listForAction(PlatformAction.QUERY, Criteria.of().in("id", userIds));
        Set<String> visibleUserIds = visibleUsers.stream()
                .map(UserAccount::getId)
                .collect(Collectors.toSet());
        if (visibleUserIds.size() != userIds.size() || !visibleUserIds.containsAll(userIds)) {
            throw new net.ximatai.muyun.spring.common.exception.PlatformException(
                    "record data permission denied: " + UserAccountService.MODULE_ALIAS + ".query");
        }
    }

    @PostMapping("/{id}/sessions/{sessionId}/revoke")
    @CustomActionEndpoint(value = "revokeSession", title = "下线会话",
            level = PlatformActionLevel.RECORD, dataAuth = true)
    @BusinessMutationResult(code = "iam.user-session.revoked", message = "登录会话已下线",
            change = BusinessMutationChange.UPDATED, module = UserAccountService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public int revokeSession(@PathVariable String id,
                             @PathVariable String sessionId,
                             HttpServletRequest request) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            if (userSessionService == null) {
                return 0;
            }
            return userSessionService.revokeUserSession(id, sessionId, bearerToken(request));
        }));
    }

    @PostMapping("/{id}/sessions/revoke")
    @CustomActionEndpoint(value = "revokeSessions", title = "批量下线会话",
            level = PlatformActionLevel.RECORD, dataAuth = true)
    @BusinessMutationResult(code = "iam.user-session.revoked-batch", message = "登录会话已下线",
            change = BusinessMutationChange.UPDATED, module = UserAccountService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public int revokeSessions(@PathVariable String id,
                              @RequestBody RevokeSessionsRequest requestBody,
                              HttpServletRequest request) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            if (userSessionService == null) {
                return 0;
            }
            return userSessionService.revokeUserSessions(id,
                    requestBody == null ? List.of() : requestBody.sessionIds(),
                    bearerToken(request));
        }));
    }

    @Override
    public Optional<String> tenantIdForCreate(UserAccount record) {
        return tenantIdForUser(record);
    }

    @Override
    public Optional<String> tenantIdForUpdate(String id, UserAccount record) {
        UserAccount existing = service().select(id);
        if (existing != null) {
            return tenantIdForUser(existing);
        }
        return tenantIdForCreate(record);
    }

    @Override
    public Optional<String> tenantIdForExistingRecord(String id) {
        return tenantIdForUser(service().select(id));
    }

    @PostMapping("/selector/query")
    @CustomActionEndpoint(value = "userSelector", title = "用户选择器", level = PlatformActionLevel.LIST,
            dataAuth = true)
    public WebPageResponse<UserSelectorItem> selector(@RequestBody(required = false) UserSelectorRequest request) {
        return webScope(() -> {
            UserSelectorRequest normalized = request == null ? UserSelectorRequest.EMPTY : request;
            Criteria criteria = selectorCriteria(normalized);
            WebPageRequest page = normalized.pageOrDefault();
            PageRequest pageRequest = PageRequest.of(page.pageNum(), page.pageSize());
            return selectorProjectedPageQuery(criteria, pageRequest, Sort.asc("username"));
        });
    }

    @GetMapping("/{id}/employee-binding")
    @CustomActionEndpoint(value = "employeeBinding", title = "绑定职员",
            level = PlatformActionLevel.RECORD, dataAuth = true)
    public UserEmployeeBindingView employeeBinding(@PathVariable String id) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            if (employeeAccountService == null || employeeService == null) {
                return UserEmployeeBindingView.empty();
            }
            EmployeeAccount binding = employeeAccountService.accountOfUser(id);
            if (binding == null) {
                return UserEmployeeBindingView.empty();
            }
            Employee employee = employeeService.select(binding.getEmployeeId());
            return UserEmployeeBindingView.from(binding, employee);
        }));
    }

    public record ChangePasswordRequest(String password) {
    }

    public record ResetPasswordResponse(int count, String temporaryPassword, java.time.Instant expiresAt) {
    }

    public record RevokeSessionsRequest(List<String> sessionIds) {
    }

    public record SessionStatusRequest(List<String> userIds) {
    }

    public record UserSelectorRequest(
            String roleId,
            String keyword,
            Boolean enabledOnly,
            WebPageRequest page
    ) {
        static final UserSelectorRequest EMPTY = new UserSelectorRequest(null, null, Boolean.TRUE, null);

        WebPageRequest pageOrDefault() {
            return page == null ? WebPageRequest.DEFAULT : page;
        }
    }

    public record UserSelectorItem(
            String id,
            String username,
            String employeeId,
            String employeeNo,
            String employeeTitle,
            String organizationId,
            String organizationTitle,
            String departmentId,
            String departmentTitle
    ) {
        static UserSelectorItem from(UserAccount user) {
            return new UserSelectorItem(
                    user.getId(),
                    user.getUsername(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        static UserSelectorItem from(Map<String, Object> record) {
            return new UserSelectorItem(
                    text(record.get("id")),
                    text(record.get("username")),
                    text(record.get("employeeId")),
                    text(record.get("employeeNo")),
                    text(record.get("employeeTitle")),
                    text(record.get("employeeOrganizationId")),
                    text(record.get("organizationTitle")),
                    text(record.get("employeeDepartmentId")),
                    text(record.get("departmentTitle"))
            );
        }

        private static String text(Object value) {
            return value == null ? null : value.toString();
        }
    }

    public record UserEmployeeBindingView(
            String bindingId,
            String employeeId,
            String employeeNo,
            String employeeTitle,
            String organizationId,
            String departmentId
    ) {
        static UserEmployeeBindingView empty() {
            return new UserEmployeeBindingView(null, null, null, null, null, null);
        }

        static UserEmployeeBindingView from(EmployeeAccount binding, Employee employee) {
            return new UserEmployeeBindingView(
                    binding.getId(),
                    binding.getEmployeeId(),
                    employee == null ? null : employee.getEmployeeNo(),
                    employee == null ? null : employee.getTitle(),
                    employee == null ? null : employee.getOrganizationId(),
                    employee == null ? null : employee.getDepartmentId()
            );
        }
    }

    private Criteria selectorCriteria(UserSelectorRequest request) {
        Criteria criteria = Criteria.of();
        if (!Boolean.FALSE.equals(request.enabledOnly())) {
            criteria.eq("enabled", Boolean.TRUE);
        }
        if (request.roleId() != null && !request.roleId().isBlank()) {
            if (roleService == null) {
                throw new IllegalStateException("role service is not available");
            }
            java.util.List<String> userIds = roleService.userIds(request.roleId());
            if (userIds.isEmpty()) {
                criteria.in("id", java.util.List.of("__none__"));
            } else {
                criteria.in("id", userIds);
            }
        }
        if (request.keyword() != null && !request.keyword().isBlank()) {
            String keyword = request.keyword().trim();
            Criteria keywordCriteria = Criteria.of();
            keywordCriteria.orGroup(Criteria.of().like("username", keyword).getRoot());
            criteria.andGroup(keywordCriteria.getRoot());
        }
        return criteria;
    }

    private WebPageResponse<UserSelectorItem> selectorProjectedPageQuery(Criteria criteria,
                                                                         PageRequest pageRequest,
                                                                         Sort sort) {
        if (staticRecordReadProjectionService == null) {
            throw new IllegalStateException("user selector projection is not available");
        }
        if (service() instanceof DataScopeAbility<?>) {
            DataScopeAbility<UserAccount> dataScopeAbility = DataScopeAbility.cast(service());
            DataScopeCriteriaResult scope = dataScopeAbility.readScopeByPolicy(selectorPolicy(), criteria);
            return dataScopeAbility.withDataScopeTenant(scope, () -> staticRecordReadProjectionService
                    .queryExplicitList(
                            UserAccountService.MODULE_ALIAS,
                            "user_selector",
                            USER_SELECTOR_OUTPUT_FIELDS,
                            service().activeCriteria(scope.criteria()),
                            pageRequest,
                            service(),
                            sort
                    )
                    .map(this::selectorItems)
                    .orElseThrow(this::unavailableUserSelectorProjection));
        }
        return staticRecordReadProjectionService.queryExplicitList(
                        UserAccountService.MODULE_ALIAS,
                        "user_selector",
                        USER_SELECTOR_OUTPUT_FIELDS,
                        service().activeCriteria(criteria),
                        pageRequest,
                        service(),
                        sort
                )
                .map(this::selectorItems)
                .orElseThrow(this::unavailableUserSelectorProjection);
    }

    private IllegalStateException unavailableUserSelectorProjection() {
        return new IllegalStateException("user selector projection is not available");
    }

    private WebPageResponse<UserSelectorItem> selectorItems(WebPageResponse<Map<String, Object>> response) {
        return new WebPageResponse<>(
                response.records().stream().map(UserSelectorItem::from).toList(),
                response.total(),
                response.pageNum(),
                response.pageSize(),
                response.pages(),
                response.totalKnown(),
                response.navigation()
        );
    }

    private ActionExecutionPolicy selectorPolicy() {
        return ActionExecutionContextHolder.current()
                .filter(context -> context.moduleAlias().equals(webScopeName()))
                .map(ActionExecutionContext::actionPolicy)
                .orElse(USER_SELECTOR_POLICY);
    }

    private Optional<String> tenantIdForUser(UserAccount user) {
        if (user == null || user.getTenantId() == null || user.getTenantId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(user.getTenantId().trim());
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request == null ? null : request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return header.substring(prefix.length()).trim();
    }
}
