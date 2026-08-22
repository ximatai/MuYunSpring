package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.web.BusinessMutation;
import net.ximatai.muyun.spring.web.BusinessMutationResultSupport;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.MutationTenantScopeExecutor;
import net.ximatai.muyun.spring.web.MutationTenantScopeResolver;
import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.iam.role.GrantableAction;
import net.ximatai.muyun.spring.iam.role.RoleGrantableActionResolver;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrant;
import net.ximatai.muyun.spring.iam.role.DataScopePolicy;
import net.ximatai.muyun.spring.iam.role.EmploymentRoleGrant;
import net.ximatai.muyun.spring.iam.role.ManagementScopeType;
import net.ximatai.muyun.spring.iam.role.Role;
import net.ximatai.muyun.spring.iam.role.RoleDataGrantActionMatrix;
import net.ximatai.muyun.spring.iam.role.RoleDataScopePolicyCatalog;
import net.ximatai.muyun.spring.iam.role.RoleOwnerScopeType;
import net.ximatai.muyun.spring.iam.role.RolePermissionAction;
import net.ximatai.muyun.spring.iam.role.RolePermissionMatrix;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.role.TenantScopePolicy;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.employee.EmployeeEmploymentReadService;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = "iam.role", title = "角色管理", route = "/iam/role")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, order = 70)
@RequestMapping("/iam.role")
public class RoleWebController extends WebSupport<RoleService> implements
        CrudWeb<Role, RoleService>,
        MutationTenantScopeResolver<Role>,
        StaticModuleUiContributor {
    private final RoleGrantableActionResolver grantableActionResolver;
    private final MenuService menuService;
    private final PlatformModuleService platformModuleService;
    private EmployeeEmploymentReadService employeeEmploymentReadService;
    private TenantApplicationService tenantApplicationService;

    public RoleWebController(RoleGrantableActionResolver grantableActionResolver) {
        this(grantableActionResolver, (MenuService) null, (PlatformModuleService) null);
    }

    /** Kept for direct controller tests and embedders that only provide menu support. */
    public RoleWebController(RoleGrantableActionResolver grantableActionResolver,
                             ObjectProvider<MenuService> menuServiceProvider) {
        this(grantableActionResolver, menuServiceProvider == null ? null : menuServiceProvider.getIfAvailable(), null);
    }

    @Autowired
    public RoleWebController(RoleGrantableActionResolver grantableActionResolver,
                             ObjectProvider<MenuService> menuServiceProvider,
                             ObjectProvider<PlatformModuleService> platformModuleServiceProvider) {
        this(grantableActionResolver,
                menuServiceProvider == null ? null : menuServiceProvider.getIfAvailable(),
                platformModuleServiceProvider == null ? null : platformModuleServiceProvider.getIfAvailable());
    }

    private RoleWebController(RoleGrantableActionResolver grantableActionResolver,
                              MenuService menuService,
                              PlatformModuleService platformModuleService) {
        this.grantableActionResolver = grantableActionResolver;
        this.menuService = menuService;
        this.platformModuleService = platformModuleService;
    }

    @Autowired(required = false)
    void setEmployeeEmploymentReadService(EmployeeEmploymentReadService employeeEmploymentReadService) {
        this.employeeEmploymentReadService = employeeEmploymentReadService;
    }

    @Autowired(required = false)
    void setTenantApplicationService(TenantApplicationService tenantApplicationService) {
        this.tenantApplicationService = tenantApplicationService;
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(RoleService.MODULE_ALIAS)
                .page(PageTemplates.listDetailCard(page -> page
                .list(list -> list.fields(fields -> fields
                        .title("角色列表")
                        .field("title", field -> field.label("角色名称").width("180px"))
                        .field("assignmentType", field -> field.label("授权层级").uiType("select").width("110px"))
                        .field("roleKind", field -> field.label("角色类型").uiType("select").width("130px"))
                        .field("sharePolicy", field -> field.label("公开策略").uiType("select").width("120px"))
                        .field("systemManaged", field -> field.label("系统托管").width("100px").align("center"))
                        .field("enabled", field -> field.label("状态").uiType("enabledStatus")
                                .width("90px").align("center"))))
                .detail(detail -> detail.editor(form -> form
                        .title("角色档案")
                        .field("title", field -> field.label("角色名称").required())
                        .field("assignmentType", field -> field.label("授权层级").required().uiType("select"))
                        .field("roleKind", field -> field.label("角色类型").required().uiType("select"))
                        .field("memberRoleIds", field -> field.label("成员角色"))
                        .field("ownerScopeType", field -> field.label("归属范围").required().readOnly().uiType("select"))
                        .field("ownerScopeId", field -> field.label("归属对象").readOnly())
                        .field("sharePolicy", field -> field.label("公开策略").required().uiType("select"))
                        .field("description", field -> field.label("说明"))
                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))
                        .field("sortOrder", field -> field.label("排序号"))))
                .traits(traits -> traits.standardCrud().enabledStatus().responsiveDetailSurface())))
                .build();
    }

    @Override
    public Optional<String> tenantIdForCreate(Role record) {
        return tenantIdForRole(record);
    }

    @Override
    public Optional<String> tenantIdForUpdate(String id, Role record) {
        Role existing = service().select(id);
        if (existing != null) {
            return tenantIdForRole(existing);
        }
        return tenantIdForCreate(record);
    }

    @Override
    public Optional<String> tenantIdForExistingRecord(String id) {
        return tenantIdForRole(service().select(id));
    }

    @GetMapping("/{roleId}/account-grants")
    @CustomActionEndpoint(value = "accountRoleGrants", title = "账号角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public List<AccountRoleGrant> accountRoleGrants(@PathVariable String roleId) {
        return roleReadScope(roleId, () -> service().accountRoleGrants(roleId));
    }

    @PostMapping("/{roleId}/account-grants")
    @CustomActionEndpoint(value = "accountRoleGrants", title = "账号角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    @BusinessMutation
    public String grantAccountRole(@PathVariable String roleId,
                                   @RequestBody AccountRoleGrantRequest request) {
        return roleRecordScope(roleId, () -> {
            RoleService.RoleGrantMutationResult result = service().grantAccountRoleResult(
                    roleId,
                    request.userId(),
                    request.managementScopeType(),
                    request.managementScopeId());
            reportAccountRoleGranted(result.changed());
            return result.grantId();
        });
    }

    @PostMapping("/{roleId}/account-grants/{grantId}/delete")
    @CustomActionEndpoint(value = "accountRoleGrants", title = "账号角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    @BusinessMutation
    public int deleteAccountRoleGrant(@PathVariable String roleId,
                                                   @PathVariable String grantId) {
        return roleRecordScope(roleId, () -> {
            int count = service().deleteAccountRoleGrant(roleId, grantId);
            reportAccountRoleRevoked(count > 0);
            return count;
        });
    }

    @GetMapping("/{roleId}/employment-grants")
    @CustomActionEndpoint(value = "employmentRoleGrants", title = "任职角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public List<EmploymentRoleGrant> employmentRoleGrants(@PathVariable String roleId) {
        return roleReadScope(roleId, () -> service().employmentRoleGrants(roleId));
    }

    @PostMapping("/{roleId}/employment-selector/query")
    @CustomActionEndpoint(value = "employmentRoleGrants", title = "任职角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public WebPageResponse<EmployeeEmploymentReadService.EmployeeEmploymentView> employmentSelector(@PathVariable String roleId,
                                                                        @RequestBody(required = false)
                                                                        EmploymentSelectorRequest request) {
        return roleReadScope(roleId, () -> {
            if (employeeEmploymentReadService == null) throw new IllegalStateException("employment selector is not available");
            EmploymentSelectorRequest normalized = request == null ? EmploymentSelectorRequest.EMPTY : request;
            WebPageRequest page = normalized.pageOrDefault();
            PageResult<EmployeeEmploymentReadService.EmployeeEmploymentView> result = employeeEmploymentReadService.page(
                    new EmployeeEmploymentReadService.Query(null, normalized.organizationId(), normalized.departmentId(),
                            normalized.enabledOnly(), PageRequest.of(page.pageNum(), page.pageSize())));
            return new WebPageResponse<>(result.getRecords(),
                    result.getTotal(), result.getPageNum(), result.getPageSize(), result.getPages(),
                    result.isTotalKnown(), null);
        });
    }

    @PostMapping("/{roleId}/employment-grants")
    @CustomActionEndpoint(value = "employmentRoleGrants", title = "任职角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    @BusinessMutation
    public String grantEmploymentRole(@PathVariable String roleId,
                                      @RequestBody EmploymentRoleGrantRequest request) {
        return roleRecordScope(roleId, () -> {
            RoleService.RoleGrantMutationResult result =
                    service().grantEmploymentRoleResult(roleId, request.employeePositionId());
            reportEmploymentRoleGranted(result.changed());
            return result.grantId();
        });
    }

    @PostMapping("/{roleId}/employment-grants/{grantId}/delete")
    @CustomActionEndpoint(value = "employmentRoleGrants", title = "任职角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    @BusinessMutation
    public int deleteEmploymentRoleGrant(@PathVariable String roleId,
                                                      @PathVariable String grantId) {
        return roleRecordScope(roleId, () -> {
            int count = service().deleteEmploymentRoleGrant(roleId, grantId);
            reportEmploymentRoleRevoked(count > 0);
            return count;
        });
    }

    private void reportAccountRoleGranted(boolean changed) {
        reportGrantMutation("iam.account-role-grant.granted", "账号角色已授权", changed);
    }

    private void reportAccountRoleRevoked(boolean changed) {
        reportGrantMutation("iam.account-role-grant.revoked", "账号角色授权已撤销", changed);
    }

    private void reportEmploymentRoleGranted(boolean changed) {
        reportGrantMutation("iam.employment-role-grant.granted", "任职角色已授权", changed);
    }

    private void reportEmploymentRoleRevoked(boolean changed) {
        reportGrantMutation("iam.employment-role-grant.revoked", "任职角色授权已撤销", changed);
    }

    private void reportGrantMutation(String code, String text, boolean changed) {
        if (changed) {
            BusinessMutationResultSupport.successCollectionChanged(code, text, RoleService.MODULE_ALIAS);
            return;
        }
        BusinessMutationResultSupport.success(code, text);
    }

    @PostMapping("/grant/{roleId}")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public int grantAction(@PathVariable String roleId,
                                        @RequestBody GrantActionRequest request) {
        return roleRecordScope(roleId, () -> service().grantAction(
                roleId,
                request.moduleAlias(),
                request.actionCode(),
                request.dataScopePolicy(),
                request.tenantScopePolicy(),
                request.scopeCondition(),
                request.referenceFieldId(),
                request.referenceActionCode()
        ));
    }

    @PostMapping("/grant/{roleId}/batch")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public int grantActions(@PathVariable String roleId,
                                         @RequestBody GrantActionsRequest request) {
        return roleRecordScope(roleId, () -> service().grantActions(
                roleId,
                request.actions().stream()
                        .map(GrantActionRequest::toCommand)
                        .toList()
        ));
    }

    @PostMapping("/revoke/{roleId}")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public int revokeAction(@PathVariable String roleId,
                                         @RequestBody RevokeActionRequest request) {
        return roleRecordScope(roleId, () -> service().revokeAction(
                roleId, request.moduleAlias(), request.actionCode()));
    }

    @PostMapping("/revoke/{roleId}/batch")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public int revokeActions(@PathVariable String roleId,
                                          @RequestBody RevokeActionsRequest request) {
        return roleRecordScope(roleId, () -> service().revokeActions(
                roleId,
                request.actions().stream()
                        .map(RevokeActionRequest::toCommand)
                        .toList()
        ));
    }

    @PostMapping("/permissionMatrix/{roleId}/replace")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    @BusinessMutation
    public int replacePermissionMatrix(@PathVariable String roleId,
                                       @RequestBody PermissionMatrixReplaceRequest request) {
        return roleRecordScope(roleId, () -> {
            int changed = service().replacePermissionActions(roleId, request.actions().stream()
                    .map(PermissionMatrixActionRequest::toCommand)
                    .toList());
            reportGrantMutation("iam.role.permission-matrix.changed", "角色授权已保存", changed > 0);
            return changed;
        });
    }

    @PostMapping("/permissionMatrix/{roleId}")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public RolePermissionMatrix permissionMatrix(@PathVariable String roleId,
                                                 @RequestBody PermissionMatrixRequest request) {
        requireReadableRole(roleId);
        return service().permissionMatrix(roleId, grantableActionResolver.resolve(request.moduleAliases()));
    }

    @GetMapping("/authorizationModules/{roleId}")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public WebListResponse<RoleAuthorizationModule> authorizationModules(@PathVariable String roleId) {
        // Read catalog access is already protected by the record-level custom action. It must not enter the
        // mutation-only tenant scope, which is unavailable for tenant read contexts.
        if (service().select(roleId) == null) {
            throw new IllegalArgumentException("role does not exist: " + roleId);
        }
        if (platformModuleService == null) {
            throw new IllegalStateException("platform module service is not available");
        }
        Role role = service().select(roleId);
        java.util.Set<String> enabledApplications = enabledApplicationsOf(role);
        List<RoleAuthorizationModule> modules = platformModuleService.listVisibleModules()
                .stream()
                .filter(module -> availableForRole(role, enabledApplications, module))
                .map(module -> new RoleAuthorizationModule(module.getId(), module.getTitle(),
                        module.getApplicationAlias(), module.getParentId()))
                .toList();
        return new WebListResponse<>(modules);
    }

    private java.util.Set<String> enabledApplicationsOf(Role role) {
        if (role == null || role.getTenantId() == null || role.getTenantId().isBlank()
                || tenantApplicationService == null) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(tenantApplicationService.availableApplicationAliases(role.getTenantId()));
    }

    private boolean availableForRole(Role role, java.util.Set<String> enabledApplications, PlatformModule module) {
        if (role == null || role.getTenantId() == null || role.getTenantId().isBlank()) {
            return true;
        }
        return enabledApplications.contains(module.getApplicationAlias());
    }

    @GetMapping("/dataGrantActionMatrix/{roleId}")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public RoleDataGrantActionMatrix dataGrantActionMatrix(@PathVariable String roleId) {
        requireReadableRole(roleId);
        return service().dataGrantActionMatrix(roleId);
    }

    @GetMapping("/dataScopePolicyCatalog/{roleId}")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public RoleDataScopePolicyCatalog dataScopePolicyCatalog(@PathVariable String roleId,
                                                             @RequestParam(required = false) String moduleAlias) {
        requireReadableRole(roleId);
        return service().dataScopePolicyCatalog(roleId, moduleAlias);
    }

    @PostMapping("/dataGrantActions/{roleId}")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    @BusinessMutation
    public int replaceDataGrantActions(@PathVariable String roleId,
                                       @RequestBody DataGrantActionsRequest request) {
        return roleRecordScope(roleId, () -> {
            int changed = service().replaceDataGrantActions(roleId, request.actions().stream()
                    .map(DataGrantActionRequest::toCommand)
                    .toList());
            reportGrantMutation("iam.role.data-grant-actions.changed", "数据授权模板已保存", changed > 0);
            return changed;
        });
    }

    @GetMapping("/menuMatrix/{roleId}/{schemeId}")
    @CustomActionEndpoint(value = "rolePermissions", title = "角色授权",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "roleId")
    public WebListResponse<RoleMenuNode> menuMatrix(@PathVariable String roleId,
                                                    @PathVariable String schemeId) {
        return roleReadScope(roleId, () -> {
            if (menuService == null) {
                throw new IllegalStateException("menu service is not available");
            }
            List<Menu> roots = menuService.rootMenus(schemeId);
            Map<String, Boolean> grantedByModule = menuGrantState(roleId, roots);
            return new WebListResponse<>(roots.stream()
                    .map(menu -> roleMenuNode(menu, grantedByModule))
                    .toList());
        });
    }

    public record AccountRoleGrantRequest(
            String userId,
            ManagementScopeType managementScopeType,
            String managementScopeId
    ) {
    }

    public record EmploymentRoleGrantRequest(
            String employeePositionId
    ) {
    }

    public record EmploymentSelectorRequest(String organizationId, String departmentId, Boolean enabledOnly,
                                            WebPageRequest page) {
        static final EmploymentSelectorRequest EMPTY = new EmploymentSelectorRequest(null, null, Boolean.TRUE,
                null);

        WebPageRequest pageOrDefault() {
            return page == null ? WebPageRequest.DEFAULT : page;
        }
    }

    public record GrantActionRequest(
            String moduleAlias,
            String actionCode,
            DataScopePolicy dataScopePolicy,
            TenantScopePolicy tenantScopePolicy,
            String scopeCondition,
            String referenceFieldId,
            String referenceActionCode
    ) {
        RoleService.ActionGrantCommand toCommand() {
            return new RoleService.ActionGrantCommand(
                    moduleAlias,
                    actionCode,
                    dataScopePolicy,
                    tenantScopePolicy,
                    scopeCondition,
                    referenceFieldId,
                    referenceActionCode
            );
        }
    }

    public record GrantActionsRequest(List<GrantActionRequest> actions) {
        public GrantActionsRequest {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record RevokeActionRequest(
            String moduleAlias,
            String actionCode
    ) {
        RoleService.ActionRevokeCommand toCommand() {
            return new RoleService.ActionRevokeCommand(moduleAlias, actionCode);
        }
    }

    public record RevokeActionsRequest(List<RevokeActionRequest> actions) {
        public RevokeActionsRequest {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record PermissionMatrixActionRequest(
            String moduleAlias,
            String actionCode,
            boolean granted,
            DataScopePolicy dataScopePolicy,
            TenantScopePolicy tenantScopePolicy,
            String scopeCondition,
            String referenceFieldId,
            String referenceActionCode
    ) {
        RoleService.PermissionActionCommand toCommand() {
            return new RoleService.PermissionActionCommand(
                    moduleAlias,
                    actionCode,
                    granted,
                    dataScopePolicy,
                    tenantScopePolicy,
                    scopeCondition,
                    referenceFieldId,
                    referenceActionCode
            );
        }
    }

    public record PermissionMatrixReplaceRequest(List<PermissionMatrixActionRequest> actions) {
        public PermissionMatrixReplaceRequest {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record PermissionMatrixRequest(List<String> moduleAliases) {
    }

    public record RoleAuthorizationModule(String moduleAlias, String title, String applicationAlias, String parentId) {
    }

    public record DataGrantActionRequest(String actionCode, DataScopePolicy dataScopePolicy, boolean enabled) {
        RoleService.DataGrantActionCommand toCommand() {
            return new RoleService.DataGrantActionCommand(actionCode, dataScopePolicy, enabled);
        }
    }

    public record DataGrantActionsRequest(List<DataGrantActionRequest> actions) {
        public DataGrantActionsRequest {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record RoleMenuNode(
            Menu menu,
            boolean granted,
            List<RoleMenuNode> children
    ) {
        public RoleMenuNode {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    private Map<String, Boolean> menuGrantState(String roleId, List<Menu> roots) {
        List<String> moduleAliases = flattenMenus(roots).stream()
                .filter(this::isModuleEntryMenu)
                .map(Menu::getModuleAlias)
                .distinct()
                .toList();
        if (moduleAliases.isEmpty()) {
            service().permissionMatrix(roleId, List.of());
            return Map.of();
        }
        RolePermissionMatrix matrix = service().permissionMatrix(roleId, moduleAliases.stream()
                .map(moduleAlias -> GrantableAction.ofPlatformDefaults(moduleAlias, PlatformAction.MENU))
                .toList());
        return matrix.modules().stream()
                .flatMap(module -> module.actions().stream())
                .collect(Collectors.toMap(
                        RolePermissionAction::moduleAlias,
                        RolePermissionAction::granted,
                        (left, right) -> left || right,
                        LinkedHashMap::new
                ));
    }

    private RoleMenuNode roleMenuNode(Menu menu, Map<String, Boolean> grantedByModule) {
        boolean granted = isModuleEntryMenu(menu)
                && Boolean.TRUE.equals(grantedByModule.get(menu.getModuleAlias()));
        return new RoleMenuNode(
                menu,
                granted,
                menuService.children(menu.getSchemeId(), menu.getId()).stream()
                        .map(child -> roleMenuNode(child, grantedByModule))
                        .toList()
        );
    }

    private boolean isModuleEntryMenu(Menu menu) {
        return menu.getModuleAlias() != null
                && !menu.getModuleAlias().isBlank();
    }

    private Optional<String> tenantIdForRole(Role role) {
        if (role == null || role.getOwnerScopeType() == RoleOwnerScopeType.PLATFORM) {
            return Optional.empty();
        }
        return Optional.of(Preconditions.requireText(role.getTenantId(),
                "tenantId is required for tenant or organization role mutation"));
    }

    private <T> T roleRecordScope(String roleId, Supplier<T> action) {
        return MutationTenantScopeExecutor.forExistingRecord(this, roleId, action);
    }

    private <T> T roleReadScope(String roleId, Supplier<T> action) {
        requireReadableRole(roleId);
        return action.get();
    }

    private void requireReadableRole(String roleId) {
        if (service().select(roleId) == null) {
            throw new IllegalArgumentException("role does not exist: " + roleId);
        }
    }

    private List<Menu> flattenMenus(List<Menu> menus) {
        if (menus == null || menus.isEmpty()) {
            return List.of();
        }
        return menus.stream()
                .flatMap(menu -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(menu),
                        flattenMenus(menuService.children(menu.getSchemeId(), menu.getId())).stream()
                ))
                .toList();
    }
}
