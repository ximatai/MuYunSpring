package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceFieldPolicy;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceReadRequest;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadRequest;
import org.springframework.stereotype.Component;

/** Admission policy for tenant-owned inline logo assets before the ordinary Tenant CRUD save binds them. */
@Component
public class TenantLogoFileReferenceUploadPolicy implements FileReferenceFieldPolicy {
    @Override
    public boolean supportsField(String moduleAlias, String relationCode, String fieldName) {
        return "iam.tenant".equals(moduleAlias) && relationCode == null
                && ("lightLogoAssetId".equals(fieldName) || "darkLogoAssetId".equals(fieldName));
    }

    @Override
    public void authorizeUpload(FileReferenceUploadRequest request) {
        if (!CurrentUserContext.isSystem()) {
            throw new PlatformException(PlatformErrorCodes.ACCESS_DENIED, 403,
                    "only system administrators can upload tenant logos");
        }
        inlineAssetOwnerTenantId(request);
    }

    @Override
    public boolean readAvailable() {
        return true;
    }

    @Override
    public void authorizeRead(FileReferenceReadRequest request) {
        if (!CurrentUserContext.isSystem()) {
            throw new PlatformException(PlatformErrorCodes.ACCESS_DENIED, 403,
                    "only system administrators can access tenant logos");
        }
        inlineAssetOwnerTenantId(request);
    }

    @Override
    public String inlineAssetOwnerTenantId(FileReferenceUploadRequest request) {
        return tenantAlias(request.draft().get("alias"));
    }

    @Override
    public String inlineAssetOwnerTenantId(FileReferenceReadRequest request) {
        return tenantAlias(request.draft().get("alias"));
    }

    private String tenantAlias(Object value) {
        if (!(value instanceof String alias) || alias.isBlank()) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "tenant alias is required before uploading a tenant logo");
        }
        return PlatformNameRules.requireIdentifier(alias, "tenantAlias");
    }
}
