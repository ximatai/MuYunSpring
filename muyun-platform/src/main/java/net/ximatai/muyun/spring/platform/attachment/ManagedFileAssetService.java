package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
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
    public static final long MAX_INLINE_BYTES = 1024 * 1024;
    private static final Pattern IMAGE_DATA_URL = Pattern.compile(
            "^data:(image/(png|jpeg|gif|webp));base64,([A-Za-z0-9+/]+={0,2})$");

    public ManagedFileAssetService(ManagedFileAssetDao dao) {
        super(MODULE_ALIAS, ManagedFileAsset.class, dao);
    }

    /** Creates an unbound inline asset. Binding and old-asset collection belong to the reference lifecycle. */
    public ManagedFileAsset createInline(String tenantId, String dataUrl) {
        if (!TransactionScopeSupport.isTransactionActive()) {
            throw new IllegalStateException("managed inline asset creation requires an active transaction");
        }
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

    /** Stores a small browser upload as a storage-neutral inline asset and returns its stable id. */
    public ManagedFileAsset createInline(String tenantId, String originalFilename, String mimeType, byte[] content) {
        if (!TransactionScopeSupport.isTransactionActive()) {
            throw new IllegalStateException("managed inline asset creation requires an active transaction");
        }
        String normalizedTenantId = requireText(tenantId, "tenantId");
        String normalizedFilename = requireText(originalFilename, "originalFilename");
        String declaredMimeType = requireText(mimeType, "mimeType").toLowerCase(Locale.ROOT);
        if (content == null || content.length == 0) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, "managed file content must not be empty");
        }
        if (content.length > MAX_INLINE_BYTES) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, "managed file content must not exceed 1024 KB");
        }
        String verifiedMimeType = requireImageMimeType(content, declaredMimeType);
        ManagedFileAsset asset = new ManagedFileAsset();
        asset.setId(Ids.newId());
        asset.setTenantId(normalizedTenantId);
        asset.setStorageKind(ManagedFileStorageKind.DATABASE_INLINE);
        asset.setContentBase64("data:" + verifiedMimeType + ";base64," + Base64.getEncoder().encodeToString(content));
        asset.setOriginalFilename(normalizedFilename);
        asset.setMimeType(verifiedMimeType);
        asset.setSizeBytes((long) content.length);
        asset.setSha256(sha256(content));
        insert(asset);
        return asset;
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
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, "managed file content must not exceed 1024 KB");
        }
        String verifiedMimeType = requireImageMimeType(bytes, matcher.group(1).toLowerCase(Locale.ROOT));
        return new InlineImage("data:" + verifiedMimeType + ";base64," + matcher.group(3), verifiedMimeType, bytes);
    }

    /** Detects the supported image formats from their binary signatures; browser multipart metadata is never trusted. */
    private String requireImageMimeType(byte[] content, String declaredMimeType) {
        String detectedMimeType = detectImageMimeType(content);
        if (detectedMimeType == null) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                    "managed file content must be a PNG, JPEG, GIF, or WebP image");
        }
        if (!detectedMimeType.equals(declaredMimeType)) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                    "managed file media type does not match its binary content");
        }
        return detectedMimeType;
    }

    private String detectImageMimeType(byte[] content) {
        if (content.length >= 8 && content[0] == (byte) 0x89 && content[1] == 0x50 && content[2] == 0x4e
                && content[3] == 0x47 && content[4] == 0x0d && content[5] == 0x0a && content[6] == 0x1a
                && content[7] == 0x0a) return "image/png";
        if (content.length >= 3 && content[0] == (byte) 0xff && content[1] == (byte) 0xd8
                && content[2] == (byte) 0xff) return "image/jpeg";
        if (content.length >= 6 && content[0] == 'G' && content[1] == 'I' && content[2] == 'F'
                && ((content[3] == '8' && content[4] == '7' && content[5] == 'a')
                || (content[3] == '8' && content[4] == '9' && content[5] == 'a'))) return "image/gif";
        if (content.length >= 12 && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') return "image/webp";
        return null;
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
