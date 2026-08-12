package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.model.file.ManagedFileStorageKind;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates and reads small, tenant-owned inline assets without exposing binary content through CRUD projections. */
@Service
public class ManagedFileAssetService extends AbstractAbilityService<ManagedFileAsset> implements SoftDeleteAbility<ManagedFileAsset> {
    public static final String MODULE_ALIAS = "platform.managed_file_asset";
    public static final long MAX_INLINE_BYTES = 512 * 1024;
    private static final Pattern IMAGE_DATA_URL = Pattern.compile(
            "^data:(image/(png|jpeg|gif|webp));base64,([A-Za-z0-9+/]+={0,2})$");

    public ManagedFileAssetService(ManagedFileAssetDao dao) {
        super(MODULE_ALIAS, ManagedFileAsset.class, dao);
    }

    /** Creates an unbound inline asset. Binding and old-asset collection belong to the reference lifecycle. */
    public ManagedFileAsset createInline(String tenantId, String dataUrl) {
        String normalizedTenantId = requireText(tenantId, "tenantId");
        if (dataUrl == null || dataUrl.isBlank()) {
            return null;
        }
        InlineImage image = parse(dataUrl);
        ManagedFileAsset asset = new ManagedFileAsset();
        asset.setId(Ids.newId());
        asset.setTenantId(normalizedTenantId);
        asset.setStorageKind(ManagedFileStorageKind.DATABASE_INLINE);
        asset.setContentBase64(image.dataUrl());
        asset.setOriginalFilename("asset." + extension(image.mimeType()));
        asset.setMimeType(image.mimeType());
        asset.setSizeBytes((long) image.bytes().length);
        asset.setSha256(sha256(image.bytes()));
        insert(asset);
        return asset;
    }

    /** Compatibility facade; callers must no longer infer replacement or deletion from this method. */
    @Deprecated(forRemoval = false)
    public ManagedFileAsset replaceInline(String tenantId, String ignoredExistingAssetId, String dataUrl) {
        return createInline(tenantId, dataUrl);
    }

    void deleteOwnedIfUnreferenced(String tenantId, String assetId, ManagedFileAssetReferenceService references) {
        if (assetId == null || assetId.isBlank() || references.isReferenced(tenantId, assetId)) return;
        deleteOwned(tenantId, assetId);
    }

    public String readInlineContent(String tenantId, String assetId) {
        ManagedFileAsset asset = requireOwned(tenantId, assetId);
        if (asset.getStorageKind() != ManagedFileStorageKind.DATABASE_INLINE || asset.getContentBase64() == null) {
            throw new IllegalStateException("managed file asset content is not stored inline: " + assetId);
        }
        return asset.getContentBase64();
    }

    /** Reads storage-neutral file facts for a governed business reference. */
    public FileTransferFileMetadata readReferenceMetadata(String tenantId, String assetId) {
        ManagedFileAsset asset = requireOwned(tenantId, assetId);
        return new FileTransferFileMetadata(asset.getId(), asset.getOriginalFilename(), extension(asset.getMimeType()),
                asset.getMimeType(), asset.getSizeBytes(), asset.getSha256(), asset.getStorageKind().name(), false,
                asset.getUpdatedAt());
    }

    public ManagedFileAsset requireOwned(String tenantId, String assetId) {
        ManagedFileAsset asset = select(requireText(assetId, "assetId"));
        if (asset == null || !requireText(tenantId, "tenantId").equals(asset.getTenantId())) {
            throw PlatformErrors.notFound("managed file asset does not exist: " + assetId, ErrorScope.module(MODULE_ALIAS));
        }
        return asset;
    }

    private void deleteOwned(String tenantId, String assetId) {
        ManagedFileAsset asset = requireOwned(tenantId, assetId);
        delete(asset);
    }

    private InlineImage parse(String dataUrl) {
        Matcher matcher = IMAGE_DATA_URL.matcher(dataUrl == null ? "" : dataUrl.trim());
        if (!matcher.matches()) throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, "managed file content must be a Base64 PNG, JPEG, GIF, or WebP data URL");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(matcher.group(3));
        } catch (IllegalArgumentException exception) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, "managed file content must contain valid Base64 data");
        }
        if (bytes.length > MAX_INLINE_BYTES) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, "managed file content must not exceed 512 KB");
        }
        return new InlineImage("data:" + matcher.group(1).toLowerCase(Locale.ROOT) + ";base64," + matcher.group(3),
                matcher.group(1).toLowerCase(Locale.ROOT), bytes);
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private String extension(String mimeType) {
        return "image/jpeg".equals(mimeType) ? "jpg" : mimeType.substring(mimeType.lastIndexOf('/') + 1);
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) value.append(String.format("%02x", item));
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record InlineImage(String dataUrl, String mimeType, byte[] bytes) {
    }
}
