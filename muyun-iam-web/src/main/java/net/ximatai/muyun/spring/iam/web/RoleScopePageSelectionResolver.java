package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.role.RoleOwnerScopeType;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.platform.web.PageContextValue;
import net.ximatai.muyun.spring.platform.web.PageSelectionContextRequest;
import net.ximatai.muyun.spring.platform.web.PageSelectionContextResolver;
import net.ximatai.muyun.spring.platform.web.ResolvedPageSelectionContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * IAM-owned translation of the role-workspace selection into immutable owner-scope fields.
 *
 * <p>This adapter deliberately knows the three role ownership shapes.  The platform only sees
 * an opaque {@value #SELECTION_KIND} key and never receives a browser-provided field map.</p>
 */
@Component
final class RoleScopePageSelectionResolver implements PageSelectionContextResolver {
    static final String SELECTION_KIND = "roleScope";
    private static final String PLATFORM_KEY = "platform";
    private static final String TENANT_PREFIX = "tenant:";
    private static final String ORGANIZATION_PREFIX = "organization:";

    private final TenantService tenantService;
    private final OrganizationService organizationService;

    RoleScopePageSelectionResolver(TenantService tenantService, OrganizationService organizationService) {
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.organizationService = Objects.requireNonNull(organizationService, "organizationService must not be null");
    }

    @Override
    public String selectionKind() {
        return SELECTION_KIND;
    }

    @Override
    public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
        requireAuthorizedRoleAction(request);
        Scope scope = resolveScope(request.selectionKey(), request.currentUser());
        return new ResolvedPageSelectionContext(SELECTION_KIND, request.selectionKey(), Map.of(
                "ownerScopeType", PageContextValue.of(scope.type()),
                "ownerScopeId", PageContextValue.of(scope.id()),
                "ownerScopeKey", PageContextValue.of(scope.key()),
                "tenantId", PageContextValue.of(scope.tenantId())
        ));
    }

    private Scope resolveScope(String selectionKey, CurrentUser currentUser) {
        if (PLATFORM_KEY.equals(selectionKey)) {
            if (!currentUser.system()) {
                throw denied("platform role scope requires a system user");
            }
            return new Scope(RoleOwnerScopeType.PLATFORM, null, PLATFORM_KEY, null);
        }
        if (selectionKey.startsWith(TENANT_PREFIX)) {
            String tenantId = requireId(selectionKey, TENANT_PREFIX, "tenant");
            tenantService.requireActiveTenant(tenantId);
            requireCurrentTenantOrSystem(currentUser, tenantId);
            return new Scope(RoleOwnerScopeType.TENANT, tenantId, TENANT_PREFIX + tenantId, tenantId);
        }
        if (selectionKey.startsWith(ORGANIZATION_PREFIX)) {
            String organizationId = requireId(selectionKey, ORGANIZATION_PREFIX, "organization");
            Organization organization = organizationService.requireEnabled(organizationId,
                    "role owner organization is not active: " + organizationId);
            String tenantId = organization.getTenantId();
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalStateException("role owner organization requires a tenant: " + organizationId);
            }
            tenantService.requireActiveTenant(tenantId);
            requireCurrentTenantOrSystem(currentUser, tenantId);
            return new Scope(RoleOwnerScopeType.ORGANIZATION, organizationId, ORGANIZATION_PREFIX + organizationId,
                    tenantId);
        }
        throw new IllegalArgumentException("unsupported role scope selection: " + selectionKey);
    }

    /**
     * The endpoint interceptor owns permission evaluation.  Requiring its authorized context here
     * prevents this resolver from becoming a side door when it is reused from a different request path.
     */
    private void requireAuthorizedRoleAction(PageSelectionContextRequest request) {
        if (!RoleService.MODULE_ALIAS.equals(request.moduleAlias())) {
            throw new IllegalArgumentException("role scope selection only supports " + RoleService.MODULE_ALIAS);
        }
        if (request.action() != PlatformAction.QUERY
                && request.action() != PlatformAction.VIEW
                && request.action() != PlatformAction.CREATE
                && request.action() != PlatformAction.UPDATE
                && request.action() != PlatformAction.DELETE
                && request.action() != PlatformAction.ENABLE
                && request.action() != PlatformAction.DISABLE
                && request.action() != PlatformAction.SORT) {
            throw denied("role scope selection does not support action: " + request.action());
        }
        ActionExecutionContext context = ActionExecutionContextHolder.current()
                .orElseThrow(() -> denied("role scope selection requires an authorized action context"));
        if (!RoleService.MODULE_ALIAS.equals(context.moduleAlias())
                || context.platformAction() != request.action()
                || context.authorizationResult() == null
                || context.currentUser().filter(user -> user.userId().equals(request.currentUser().userId())).isEmpty()) {
            throw denied("role scope selection action context does not match the request");
        }
    }

    private void requireCurrentTenantOrSystem(CurrentUser currentUser, String tenantId) {
        if (!currentUser.system() && !tenantId.equals(currentUser.tenantId())) {
            throw denied("role scope does not belong to the current tenant");
        }
    }

    private String requireId(String selectionKey, String prefix, String scopeName) {
        String id = selectionKey.substring(prefix.length());
        if (id.isBlank() || id.indexOf(':') >= 0) {
            throw new IllegalArgumentException(scopeName + " role scope selection is invalid: " + selectionKey);
        }
        return id;
    }

    private PlatformAccessDeniedException denied(String message) {
        return new PlatformAccessDeniedException(message);
    }

    private record Scope(RoleOwnerScopeType type, String id, String key, String tenantId) {
    }
}
