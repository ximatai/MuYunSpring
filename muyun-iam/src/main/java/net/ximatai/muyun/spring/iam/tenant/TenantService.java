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
import net.ximatai.muyun.database.core.orm.Criteria;
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

import java.util.Base64;
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
    public void normalizeBeforeMutation(Tenant tenant) {
        tenant.setAlias(requireTenantAlias(tenant.getAlias()));
        tenant.setTenantId(null);
        if (tenant.getWorkbenchBrandMode() == null) {
            tenant.setWorkbenchBrandMode(TenantWorkbenchBrandMode.LOGO_WITH_TITLE);
        }
        validateWorkbenchBranding(tenant);
    }

    @Override
    public void beforeUpdate(Tenant tenant) {
        requireSystemMutationContext();
        normalizeBeforeMutation(tenant);
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
    public void beforeDelete(String id) {
        requireSystemMutationContext();
    }

    @Override
    public void afterInsert(String id, Tenant tenant) {
        provisionTenant(id);
    }

    public void provisionTenant(String tenantId) {
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
    public Criteria recycleBinCriteria(Criteria criteria) {
        return globalCriteria(criteria);
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
        return assetId == null || assetId.isBlank() || managedFileAssetService == null
                ? null
                : managedFileAssetService.readInlineContent(tenantId, assetId);
    }

    private void validateWorkbenchBranding(Tenant tenant) {
        if (tenant.getWorkbenchBrandMode() != TenantWorkbenchBrandMode.LOGO_WITH_TITLE
                || managedFileAssetService == null) {
            return;
        }
        requireSquareLogo(tenant.getId(), tenant.getLightLogoAssetId(), "展示 Logo（默认）");
        requireSquareLogo(tenant.getId(), tenant.getDarkLogoAssetId(), "展示 Logo（暗色模式）");
    }

    private void requireSquareLogo(String tenantId, String assetId, String fieldLabel) {
        if (assetId == null || assetId.isBlank()) return;
        String content = managedFileAssetService.readInlineContent(tenantId, assetId);
        int comma = content.indexOf(',');
        if (comma < 0) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, fieldLabel + "不是可读取的图片");
        }
        try {
            ImageDimensions image = imageDimensions(Base64.getDecoder().decode(content.substring(comma + 1)));
            if (image == null || image.width() <= 0 || image.height() <= 0) {
                throw new IllegalArgumentException("image dimensions are unavailable");
            }
            double ratio = (double) image.width() / image.height();
            if (ratio < 0.9 || ratio > 1.1) {
                throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                        fieldLabel + "在“Logo + 标题”模式下必须为正方形图片");
            }
        } catch (PlatformException exception) {
            throw exception;
        } catch (Exception exception) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                    fieldLabel + "不是可读取的图片");
        }
    }

    private ImageDimensions imageDimensions(byte[] content) {
        if (content.length >= 24 && content[0] == (byte) 0x89 && content[1] == 0x50
                && content[2] == 0x4e && content[3] == 0x47) {
            return new ImageDimensions(unsignedInt(content, 16), unsignedInt(content, 20));
        }
        if (content.length >= 10 && content[0] == 'G' && content[1] == 'I' && content[2] == 'F') {
            return new ImageDimensions(littleEndianShort(content, 6), littleEndianShort(content, 8));
        }
        if (content.length >= 30 && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return webpDimensions(content);
        }
        return jpegDimensions(content);
    }

    private ImageDimensions webpDimensions(byte[] content) {
        if (fourCc(content, 12, "VP8X") && content.length >= 30) {
            return new ImageDimensions(littleEndian24(content, 24) + 1, littleEndian24(content, 27) + 1);
        }
        if (fourCc(content, 12, "VP8 ") && content.length >= 30) {
            return new ImageDimensions(littleEndianShort(content, 26) & 0x3fff,
                    littleEndianShort(content, 28) & 0x3fff);
        }
        if (fourCc(content, 12, "VP8L") && content.length >= 25 && content[20] == 0x2f) {
            int width = 1 + unsigned(content[21]) + ((unsigned(content[22]) & 0x3f) << 8);
            int height = 1 + (unsigned(content[22]) >> 6) + (unsigned(content[23]) << 2)
                    + ((unsigned(content[24]) & 0x0f) << 10);
            return new ImageDimensions(width, height);
        }
        return null;
    }

    private ImageDimensions jpegDimensions(byte[] content) {
        if (content.length < 4 || content[0] != (byte) 0xff || content[1] != (byte) 0xd8) return null;
        for (int index = 2; index + 8 < content.length;) {
            if (content[index++] != (byte) 0xff) continue;
            while (index < content.length && content[index] == (byte) 0xff) index++;
            if (index >= content.length) return null;
            int marker = unsigned(content[index++]);
            if (marker == 0xd8 || marker == 0xd9) continue;
            if (index + 1 >= content.length) return null;
            int length = (unsigned(content[index]) << 8) + unsigned(content[index + 1]);
            if (length < 2 || index + length > content.length) return null;
            if ((marker >= 0xc0 && marker <= 0xc3) || (marker >= 0xc5 && marker <= 0xc7)
                    || (marker >= 0xc9 && marker <= 0xcb) || (marker >= 0xcd && marker <= 0xcf)) {
                return new ImageDimensions((unsigned(content[index + 6]) << 8) + unsigned(content[index + 7]),
                        (unsigned(content[index + 3]) << 8) + unsigned(content[index + 4]));
            }
            index += length;
        }
        return null;
    }

    private boolean fourCc(byte[] content, int offset, String value) {
        return content.length >= offset + 4 && content[offset] == value.charAt(0) && content[offset + 1] == value.charAt(1)
                && content[offset + 2] == value.charAt(2) && content[offset + 3] == value.charAt(3);
    }

    private int unsigned(byte value) { return Byte.toUnsignedInt(value); }

    private int littleEndianShort(byte[] content, int offset) {
        return unsigned(content[offset]) + (unsigned(content[offset + 1]) << 8);
    }

    private int littleEndian24(byte[] content, int offset) {
        return littleEndianShort(content, offset) + (unsigned(content[offset + 2]) << 16);
    }

    private int unsignedInt(byte[] content, int offset) {
        return (unsigned(content[offset]) << 24) + (unsigned(content[offset + 1]) << 16)
                + (unsigned(content[offset + 2]) << 8) + unsigned(content[offset + 3]);
    }

    private record ImageDimensions(int width, int height) {
    }

}
