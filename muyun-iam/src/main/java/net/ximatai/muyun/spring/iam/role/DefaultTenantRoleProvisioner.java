package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.tenant.TenantCreationProvisioner;
import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.iam.role.Role;
import net.ximatai.muyun.spring.iam.role.ManagementScopeType;
import net.ximatai.muyun.spring.iam.role.RoleService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public class DefaultTenantRoleProvisioner implements TenantCreationProvisioner {
    public static final String TENANT_ADMIN_ROLE_DESCRIPTION =
            "租户内置管理员角色，拥有当前租户内平台可授权动作和全部数据范围。";
    private static final String SYSTEM_OPERATOR_ID = "tenant-provisioner";
    private static final String ROLE_ID_PREFIX = "tenant_admin_";
    private static final int HASH_LENGTH = 16;

    private final RoleService roleService;
    public DefaultTenantRoleProvisioner(RoleService roleService) {
        this.roleService = Objects.requireNonNull(roleService, "roleService must not be null");
    }

    /**
     * @deprecated Tenant-admin access is interpreted from its platform role purpose at runtime;
     * the action template is intentionally ignored. Kept for source compatibility with embedders.
     */
    @Deprecated(forRemoval = false)
    public DefaultTenantRoleProvisioner(RoleService roleService,
                                        BuiltInRolePermissionTemplateService ignoredRolePermissionTemplateService) {
        this(roleService);
    }

    @Override
    public void afterTenantCreated(String tenantId) {
        ensureTenantAdminRole(tenantId);
    }

    public Role ensureTenantAdminRole(String tenantId) {
        String validTenantId = Preconditions.requireText(tenantId, "tenantId");
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.systemUser(SYSTEM_OPERATOR_ID, "Tenant Provisioner"));
             TenantContext.Scope ignoredTenant = TenantContext.use(validTenantId)) {
            Role role = roleService.ensureSystemManagedTenantAdminRole(
                    validTenantId,
                    tenantAdminRoleId(validTenantId),
                    RoleService.TENANT_ADMIN_ROLE_TITLE,
                    TENANT_ADMIN_ROLE_DESCRIPTION
            );
            return role;
        }
    }

    public Role grantTenantAdminRoleToUser(String tenantId, String userId) {
        String validTenantId = Preconditions.requireText(tenantId, "tenantId");
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.systemUser(SYSTEM_OPERATOR_ID, "Tenant Provisioner"));
             TenantContext.Scope ignoredTenant = TenantContext.use(validTenantId)) {
            Role role = ensureTenantAdminRole(validTenantId);
            roleService.grantAccountRole(role.getId(), Preconditions.requireText(userId, "userId"),
                    ManagementScopeType.TENANT, validTenantId);
            return role;
        }
    }

    public static String tenantAdminRoleId(String tenantId) {
        return ROLE_ID_PREFIX + shortHash(Preconditions.requireText(tenantId, "tenantId"));
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
