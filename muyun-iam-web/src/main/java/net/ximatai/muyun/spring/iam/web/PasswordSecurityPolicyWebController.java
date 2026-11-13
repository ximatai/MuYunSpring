package net.ximatai.muyun.spring.iam.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.application.IamApplication;
import net.ximatai.muyun.spring.iam.user.EffectivePasswordRule;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRule;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicy;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicyMode;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicyResolver;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicyService;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityWorkspaceService;
import net.ximatai.muyun.spring.iam.user.ResolvedPasswordSecurityPolicy;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.ActionEndpointContextResolver;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.NavigatorListQueryMode;
import net.ximatai.muyun.spring.platform.web.PageContextScopePolicy;
import net.ximatai.muyun.spring.platform.web.PageNavigatorSingleResultPolicy;
import net.ximatai.muyun.spring.platform.web.PageNavigatorSourceScope;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.web.RecordSaveRequestSupport;
import net.ximatai.muyun.spring.platform.web.StaticModuleTenantScopePolicy;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.StaticModuleWebControllerAdapter;
import net.ximatai.muyun.spring.platform.web.UiFormula;
import net.ximatai.muyun.spring.platform.web.UiRule;
import net.ximatai.muyun.spring.web.BusinessMutation;
import net.ximatai.muyun.spring.web.MutationTenantScopeExecutor;
import net.ximatai.muyun.spring.web.PlatformAuditMutationGuard;
import net.ximatai.muyun.spring.web.WebOutputSupport;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified "Settings -> Password security" surface for the platform default policy and per-tenant
 * overrides. Password complexity rules use the standard {@code iam.password_policy_rule} CRUD module,
 * embedded in this workspace with its action permissions, versioning, enable/sort and tenant scope.
 * The Web boundary adapts the domain workspace command and exposes an effective-policy snapshot.
 *
 * <p>Super administrators pick the platform default (system workspace, no selected tenant) or any
 * tenant; tenant administrators are pinned to their own tenant. All reads and writes go through the
 * standard module action permissions and tenant scope.</p>
 */
@RestController
@PlatformStaticModule(application = IamApplication.class,
        alias = PasswordSecurityPolicyService.MODULE_ALIAS, title = "密码安全策略")
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "密码安全", order = 10)
@StaticModuleTenantScopePolicy(requireActiveTenant = false)
@RequestMapping("/iam.password_security_policy")
public class PasswordSecurityPolicyWebController
        extends StaticModuleWebControllerAdapter<PasswordSecurityPolicyService>
        implements CrudWeb<PasswordSecurityPolicy, PasswordSecurityPolicyService>, StaticModuleUiContributor {
    private final PasswordSecurityPolicyResolver resolver;
    private final PasswordSecurityWorkspaceService workspace;
    private final ActionExecutionPolicyService actionPolicy;
    private final ActionEndpointContextResolver actionResolver;

    public PasswordSecurityPolicyWebController(PasswordSecurityPolicyResolver resolver,
                                              PasswordSecurityWorkspaceService workspace,
                                              ActionExecutionPolicyService policy,
                                              ActionEndpointContextResolver actionResolver) {
        this.resolver = Objects.requireNonNull(resolver, "passwordSecurityPolicyResolver must not be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.actionPolicy = Objects.requireNonNull(policy, "actionPolicy must not be null");
        this.actionResolver = Objects.requireNonNull(actionResolver, "actionResolver must not be null");
    }

    /**
     * The receipt and domain command share the platform transaction. The command authorizes each
     * standard policy/rule action, so this transport does not require an additional custom grant.
     */
    @PostMapping("/workspace-save")
    @CustomActionEndpoint(value = "savePasswordSecurity", title = "保存密码安全配置", level = PlatformActionLevel.LIST, actionAuth = false)
    @BusinessMutation
    public WorkspaceSaveResult saveWorkspace(@RequestBody WorkspaceSave request) {
        Objects.requireNonNull(request, "request");
        String target = resolveTargetTenant(request.tenantId());
        String action = request.policy() != null && request.policy().getId() == null ? "create" : "update";
        PasswordSecurityPolicy saved = RecordSaveRequestSupport.execute(this, action,
                request.policy() == null ? null : request.policy().getId(), request, () ->
                MutationTenantScopeExecutor.forAuthoritativeTenantScope(target, () -> webScope(() ->
                    workspace.save(new PasswordSecurityWorkspaceService.Command(target, request.policy(),
                            request.originalRules() == null ? null : request.originalRules().stream()
                                    .map(v -> new PasswordSecurityWorkspaceService.RuleVersion(v.id(), v.version())).toList(),
                            request.rules()), new PasswordSecurityWorkspaceService.Access() {
                        public void authorize(String module, PlatformAction operation, String id) {
                            PasswordSecurityPolicyWebController.this.authorize(module, operation, id);
                        }
                        public void validate(EntityContract supplied, EntityContract existing) {
                            PlatformAuditMutationGuard.validate(supplied, existing);
                        }
                    }))));
        return new WorkspaceSaveResult(WebOutputSupport.record(service(), saved,
                FieldOutputContext.VIEW));
    }

    private void authorize(String module, PlatformAction action, String id) {
        actionPolicy.requireAuthorized(actionResolver.resolveActionCode(
                        module, action.code(), id == null ? Set.of() : Set.of(id)));
    }

    public record RuleVersion(String id, Integer version) { }
    public record WorkspaceSave(String tenantId, PasswordSecurityPolicy policy,
                                List<RuleVersion> originalRules, List<PasswordPolicyRule> rules) { }
    public record WorkspaceSaveResult(PasswordSecurityPolicy record) { }

    /** The immutable effective policy for the selected target, including rule and lockout sources. */
    @GetMapping("/effective")
    @CustomActionEndpoint(value = "effectivePasswordPolicy", title = "查看有效密码策略",
            level = PlatformActionLevel.LIST)
    public EffectivePolicyView effective(@RequestParam(required = false) String tenantId) {
        String target = resolveTargetTenant(tenantId);
        PasswordSecurityPolicy stored = service().findManagedByScope(target).orElse(null);
        ResolvedPasswordSecurityPolicy snapshot = resolver.resolve(target);
        String complexityMode = target == null
                ? PasswordSecurityPolicyMode.CUSTOM.getCode()
                : modeCode(stored == null ? null : stored.getComplexityMode());
        String lockoutMode = target == null
                ? PasswordSecurityPolicyMode.CUSTOM.getCode()
                : modeCode(stored == null ? null : stored.getLockoutMode());
        return new EffectivePolicyView(
                target,
                stored == null ? null : stored.getId(),
                complexityMode,
                lockoutMode,
                stored == null ? null : stored.getMaxFailedAttempts(),
                stored == null ? null : stored.getLockDurationSeconds(),
                snapshot.complexitySource().name(),
                snapshot.complexityRules().stream().map(RuleView::from).toList(),
                (target == null ? snapshot : resolver.resolve(null)).complexityRules().stream().map(RuleView::from).toList(),
                snapshot.lockoutSource().name(),
                snapshot.maxFailedAttempts(),
                snapshot.lockDuration() == null ? null : (int) snapshot.lockDuration().toSeconds());
    }

    private String resolveTargetTenant(String requested) {
        String requestedTenant = requested == null || requested.isBlank() ? null : requested.trim();
        if (CurrentUserContext.isSystem()) {
            if (requestedTenant != null) {
                return requestedTenant;
            }
            return TenantContext.currentTenantId().orElse(null);
        }
        String current = TenantContext.currentTenantId().orElse(null);
        if (current == null) {
            throw new PlatformAccessDeniedException("password security policy requires a tenant context");
        }
        if (requestedTenant != null && !requestedTenant.equals(current)) {
            throw new PlatformAccessDeniedException("cannot read another tenant's password security policy");
        }
        return current;
    }

    private static String modeCode(PasswordSecurityPolicyMode mode) {
        return (mode == null ? PasswordSecurityPolicyMode.INHERIT : mode).getCode();
    }

    /** System super administrators see the platform default when no business tenant is selected. */
    @Override
    @PostMapping("/query")
    @ActionEndpoint(PlatformAction.QUERY)
    public WebPageResponse<PasswordSecurityPolicy> query(
            @RequestBody(required = false) WebQueryRequest request) {
        return CrudWeb.super.query(queryForCurrentWorkspace(request));
    }

    WebQueryRequest queryForCurrentWorkspace(WebQueryRequest request) {
        if (PageContextScopePolicy.hasContextValue("tenant") || hasSelectedTenantNavigator(request)) return request;
        boolean systemUser = CurrentUserContext.currentUser().map(currentUser -> currentUser.system()).orElse(false);
        if (!systemUser) return request;
        List<WebQueryCondition> conditions = new ArrayList<>(request == null ? List.of() : request.conditions());
        conditions.add(new WebQueryCondition("tenantId", "NULL", List.of()));
        if (request == null) return new WebQueryRequest(null, conditions, List.of());
        return new WebQueryRequest(request.page(), request.unpaged(), conditions, request.criteria(), request.queryForm(),
                request.sorts(), request.uiConfigId(), request.queryTemplateId(), request.externalQueryValues(),
                request.navigationSession(), request.quickSearch(), request.quickSearchFields(), request.navigationQueryKey(),
                request.navigatorHostModuleAlias(), request.navigatorTargetLevelKey());
    }

    private boolean hasSelectedTenantNavigator(WebQueryRequest request) {
        if (request == null) return false;
        Object tenantId = request.externalQueryValues().get("tenantId");
        return tenantId != null && !String.valueOf(tenantId).isBlank();
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(PasswordSecurityPolicyService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .navigator(navigator -> navigator
                                .level("tenant", level -> level
                                        .microList("iam.tenant", "配置范围", "搜索租户")
                                        .sourceScope(PageNavigatorSourceScope.CURRENT_TENANT)
                                        .singleResultPolicy(PageNavigatorSingleResultPolicy.AUTO_SELECT_AND_HIDE))
                                .filterListByNavigator("tenant", "tenantId", NavigatorListQueryMode.OPTIONAL_FILTER)
                                .prefillFormFromNavigator("tenant", "tenantId"))
                        .explorer(explorer -> explorer.title("密码安全策略"))
                        .detail(detail -> detail
                                .emptyDescription("当前租户未配置覆盖策略，继承平台默认。")
                                .createTitle("自定义租户策略")
                                .editor(form -> form.title("密码安全策略")
                                        .group("complexity", "密码复杂度", "平台规则作为默认；租户可继承或自定义整组规则。", group -> group
                                                .field("complexityMode", field -> field.label("规则来源").select()
                                                        .visible(UiRule.formula(UiFormula.booleanExpression("PRESENT({tenantId})")))
                                                        .required(UiRule.formula(UiFormula.booleanExpression("PRESENT({tenantId})")))))
                                        .group("lockout", "登录失败锁定", "阈值设为 0 可关闭新锁定；已有锁定保留原到期时间。", group -> group
                                                .field("lockoutMode", field -> field.label("配置来源").select()
                                                        .visible(UiRule.formula(UiFormula.booleanExpression("PRESENT({tenantId})")))
                                                        .required(UiRule.formula(UiFormula.booleanExpression("PRESENT({tenantId})"))))
                                                .field("maxFailedAttempts", field -> field.label("失败次数阈值")
                                                        .visible(UiRule.formula(UiFormula.booleanExpression("!(PRESENT({tenantId})) || {lockoutMode} == 'custom'")))
                                                        .required(UiRule.formula(UiFormula.booleanExpression("!(PRESENT({tenantId})) || {lockoutMode} == 'custom'"))))
                                                .field("lockDurationSeconds", field -> field.label("锁定时长（秒）")
                                                        .visible(UiRule.formula(UiFormula.booleanExpression("(!(PRESENT({tenantId})) || {lockoutMode} == 'custom') && PRESENT({maxFailedAttempts}) && {maxFailedAttempts} != 0")))
                                                        .required(UiRule.formula(UiFormula.booleanExpression("(!(PRESENT({tenantId})) || {lockoutMode} == 'custom') && PRESENT({maxFailedAttempts}) && {maxFailedAttempts} != 0"))))))
                                .showSystemInfo(false))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud()))))
                .build();
    }

    public record RuleView(String id, String tenantId, String pattern, String message) {
        static RuleView from(EffectivePasswordRule rule) {
            return new RuleView(rule.id(), rule.tenantId(), rule.pattern(), rule.message().originalText());
        }
    }

    public record EffectivePolicyView(
            String tenantId,
            String policyId,
            String complexityMode,
            String lockoutMode,
            Integer configuredMaxFailedAttempts,
            Integer configuredLockDurationSeconds,
            String complexitySource,
            List<RuleView> complexityRules,
            List<RuleView> platformComplexityRules,
            String lockoutSource,
            int maxFailedAttempts,
            Integer lockDurationSeconds
    ) {
    }
}
