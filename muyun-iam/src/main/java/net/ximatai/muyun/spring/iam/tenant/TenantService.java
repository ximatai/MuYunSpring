package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.GlobalScopedAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.SystemManagedAbility;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionRecoveryAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantCreationProvisioner;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.attachment.ManagedFileAssetService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TenantService extends AbstractAbilityService<Tenant> implements
        SystemManagedAbility<Tenant>,
        GlobalScopedAbility<Tenant>,
        RecycleBinAbility<Tenant>,
        DeletionRecoveryAbility<Tenant>,
        EnableAbility<Tenant>,
        SortAbility<Tenant>,
        ReferenceAbility<Tenant>,
        QueryAbility<Tenant>,
        ChildrenAbility<Tenant>,
        ActiveTenantVerifier {

    public static final String MODULE_ALIAS = "iam.tenant";
    private final ObjectProvider<TenantCreationProvisioner> creationProvisioners;
    private final TenantApplicationService tenantApplicationService;
    private final ManagedFileAssetService managedFileAssetService;

    public TenantService(TenantDao tenantDao) {
        super(MODULE_ALIAS, Tenant.class, tenantDao);
        this.creationProvisioners = null;
        this.tenantApplicationService = null;
        this.managedFileAssetService = null;
    }

    public TenantService(TenantDao tenantDao, ObjectProvider<TenantCreationProvisioner> creationProvisioners) {
        super(MODULE_ALIAS, Tenant.class, tenantDao);
        this.creationProvisioners = creationProvisioners;
        this.tenantApplicationService = null;
        this.managedFileAssetService = null;
    }

    public TenantService(TenantDao tenantDao,
                         ObjectProvider<TenantCreationProvisioner> creationProvisioners,
                         TenantApplicationService tenantApplicationService) {
        super(MODULE_ALIAS, Tenant.class, tenantDao);
        this.creationProvisioners = creationProvisioners;
        this.tenantApplicationService = tenantApplicationService;
        this.managedFileAssetService = null;
    }

    @Autowired
    public TenantService(TenantDao tenantDao,
                         ObjectProvider<TenantCreationProvisioner> creationProvisioners,
                         TenantApplicationService tenantApplicationService,
                         ManagedFileAssetService managedFileAssetService) {
        super(MODULE_ALIAS, Tenant.class, tenantDao);
        this.creationProvisioners = creationProvisioners;
        this.tenantApplicationService = tenantApplicationService;
        this.managedFileAssetService = managedFileAssetService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, Tenant.class,
                List.of("id", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                Sort.asc("sortOrder"),
                Sort.asc("title"));
    }

    @Override
    public void normalizeBeforeMutation(Tenant tenant) {
        tenant.setAlias(requireTenantAlias(tenant.getAlias()));
        tenant.setTenantId(null);
        if (tenant.getWorkbenchBrandMode() == null) {
            tenant.setWorkbenchBrandMode(TenantWorkbenchBrandMode.LOGO_WITH_TITLE);
        }
        validateWorkbenchBranding(tenant);
    }

    @Override
    public void beforeInsert(Tenant tenant) {
        Tenant existing = selectIgnoreSoftDelete(tenant.getId());
        if (existing != null && Boolean.TRUE.equals(existing.getDeleted())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("resourceModuleAlias", MODULE_ALIAS);
            details.put("resourceRecordId", existing.getId());
            if (existing.getDeletedAt() != null) {
                details.put("deletedAt", existing.getDeletedAt());
            }
            details.put("recoveryAvailable", Boolean.TRUE);
            throw PlatformErrors.conflict(PlatformErrorCodes.RESOURCE_SOFT_DELETED_CONFLICT,
                    "Tenant alias is retained by a soft-deleted tenant; restore it from the recycle bin before creating it again",
                    details);
        }
    }

    @Override
    public void afterInsert(String id, Tenant tenant) {
        notifyTenantCreated(id);
    }

    /** Replays idempotent initialization for an active tenant in a system mutation transaction. */
    public void provisionTenant(String tenantId) {
        inMutationTransaction(() -> {
            requireSystemMutationContext();
            Tenant tenant = requireActiveTenant(tenantId);
            notifyTenantCreated(tenant.getId());
            return null;
        });
    }

    private void notifyTenantCreated(String tenantId) {
        if (creationProvisioners != null) {
            creationProvisioners.orderedStream().forEach(provisioner -> provisioner.afterTenantCreated(tenantId));
        }
    }

    public Tenant requireActiveTenant(String tenantAlias) {
        String alias = requireTenantAlias(tenantAlias);
        return requireEnabled(alias, "Tenant is not active: " + alias);
    }

    /** Returns the small, session-scoped branding projection used by the workbench shell. */
    public TenantBranding branding(String tenantAlias) {
        Tenant tenant = selectIgnoreSoftDelete(requireTenantAlias(tenantAlias));
        if (tenant == null) return TenantBranding.empty();
        return new TenantBranding(contentOf(tenant.getId(), tenant.getLightLogoAssetId()),
                contentOf(tenant.getId(), tenant.getDarkLogoAssetId()), tenant.getWorkbenchBrandMode() == null
                ? TenantWorkbenchBrandMode.LOGO_WITH_TITLE.getCode() : tenant.getWorkbenchBrandMode().getCode(),
                tenant.getWorkbenchTitle(), tenant.getWorkbenchSubtitle());
    }

    @Override
    public void beforeRecycleBinQuery() {
        requireSystemMutationContext();
    }

    @Override
    public void beforeRecycleBinRestore() {
        requireSystemMutationContext();
    }

    @Override
    public void verifyActiveTenant(String tenantId) {
        requireActiveTenant(tenantId);
    }

    @Override
    public List<ChildRelation<? extends EntityContract, Tenant>> childRelations() {
        return tenantApplicationService == null
                ? List.of()
                : List.of(childRelation(tenantApplicationService));
    }

    /** Tenant applications are optional in lightweight IAM runtime assemblies. */
    @Override
    public boolean usesAutomaticChildRelations() {
        return false;
    }

    private String requireTenantAlias(String alias) {
        return PlatformNameRules.requireIdentifier(alias, "tenantAlias");
    }

    private String contentOf(String tenantId, String assetId) {
        return assetId == null || assetId.isBlank() ? null : requireFileAssets().readInlineContent(tenantId, assetId);
    }

    private ManagedFileAssetService requireFileAssets() {
        if (managedFileAssetService == null) {
            throw new IllegalStateException("tenant branding requires ManagedFileAssetService");
        }
        return managedFileAssetService;
    }

    private void validateWorkbenchBranding(Tenant tenant) {
        if (tenant.getWorkbenchBrandMode() != TenantWorkbenchBrandMode.LOGO_WITH_TITLE) {
            return;
        }
        requireSquareLogo(tenant.getId(), tenant.getLightLogoAssetId(), "展示 Logo（默认）");
        requireSquareLogo(tenant.getId(), tenant.getDarkLogoAssetId(), "展示 Logo（暗色模式）");
    }

    private void requireSquareLogo(String tenantId, String assetId, String fieldLabel) {
        if (assetId == null || assetId.isBlank()) return;
        var metadata = requireFileAssets().readReferenceMetadata(tenantId, assetId);
        if (metadata.imageWidth() == null || metadata.imageHeight() == null) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, fieldLabel + "缺少图片尺寸信息");
        }
        double ratio = (double) metadata.imageWidth() / metadata.imageHeight();
        if (ratio < 0.9 || ratio > 1.1) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                    fieldLabel + "在“Logo + 标题”模式下必须为正方形图片");
        }
    }
}
