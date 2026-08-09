package net.ximatai.muyun.spring.starter.configuration.filetransfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccess;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccessService;
import net.ximatai.muyun.spring.platform.attachment.FileTransferOperation;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachment;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentAccess;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentAccessService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Official, stateless adapter for MuYunFileServer's public transfer-token protocol.
 * It never persists file metadata: the file server remains the file fact source.
 */
public final class MuYunFileServerTransferAccessService
        implements FileTransferAccessService, RecordAttachmentAccessService {
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final MuYunFileServerTransferProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MuYunFileServerTransferAccessService(MuYunFileServerTransferProperties properties,
                                                 ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    MuYunFileServerTransferAccessService(MuYunFileServerTransferProperties properties,
                                         ObjectMapper objectMapper,
                                         Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        validateConfiguration();
    }

    @Override
    public FileTransferAccess issueUploadAccess() {
        return issue(FileTransferOperation.UPLOAD, null, properties.getUploadTokenTtl());
    }

    @Override
    public FileTransferAccess issueMetadataAccess(String fileId) {
        return issue(FileTransferOperation.METADATA, fileId, properties.getMetadataTokenTtl());
    }

    @Override
    public FileTransferAccess issuePromoteAccess(String fileId) {
        return issue(FileTransferOperation.PROMOTE, fileId, properties.getPromoteTokenTtl());
    }

    @Override
    public FileTransferAccess issueDeleteAccess(String fileId) {
        return issue(FileTransferOperation.DELETE, fileId, properties.getDeleteTokenTtl());
    }

    @Override
    public FileTransferAccess issuePreviewAccess(String fileId) {
        return issue(FileTransferOperation.PREVIEW, fileId, properties.getPreviewTokenTtl());
    }

    @Override
    public FileTransferAccess issueDownloadAccess(String fileId) {
        return issue(FileTransferOperation.DOWNLOAD, fileId, properties.getDownloadTokenTtl());
    }

    @Override
    public RecordAttachmentAccess issueUploadAccess(String moduleAlias, String recordId) {
        return attachmentAccess("upload", issueUploadAccess());
    }

    @Override
    public RecordAttachmentAccess issuePreviewAccess(String moduleAlias, String recordId, RecordAttachment attachment) {
        return attachmentAccess("preview", issuePreviewAccess(requireFileId(attachment)));
    }

    @Override
    public RecordAttachmentAccess issueDownloadAccess(String moduleAlias, String recordId, RecordAttachment attachment) {
        return attachmentAccess("download", issueDownloadAccess(requireFileId(attachment)));
    }

    private FileTransferAccess issue(FileTransferOperation operation, String fileId, Duration ttl) {
        CurrentUser user = CurrentUserContext.currentUser()
                .orElseThrow(() -> new PlatformException("file transfer requires an authenticated user"));
        String scopeId = resolveStorageScopeId(user);
        String normalizedFileId = operation == FileTransferOperation.UPLOAD ? null : requireText(fileId, "fileId");
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(requirePositive(ttl, operation));
        String token = signToken(user, scopeId, operation, normalizedFileId, issuedAt, expiresAt);
        return new FileTransferAccess(operation, normalizedFileId, token, targetUrl(operation, normalizedFileId, token),
                expiresAt);
    }

    private String signToken(CurrentUser user,
                             String scopeId,
                             FileTransferOperation operation,
                             String fileId,
                             Instant issuedAt,
                             Instant expiresAt) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getIssuer());
        claims.put("sub", user.userId());
        claims.put("purpose", fileServerPurpose(operation));
        claims.put("tenant_id", scopeId);
        if (fileId != null) {
            claims.put("file_id", fileId);
        }
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        try {
            byte[] payload = objectMapper.writeValueAsBytes(claims);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize file transfer token", exception);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to sign file transfer token", exception);
        }
    }

    private String targetUrl(FileTransferOperation operation, String fileId, String token) {
        String baseUrl = properties.getBaseUrl().toString().replaceAll("/+$", "");
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
        return switch (operation) {
            case UPLOAD -> baseUrl + "/api/v1/public/files?access_token=" + encodedToken;
            case METADATA -> baseUrl + "/api/v1/public/files/" + fileId + "?access_token=" + encodedToken;
            case PROMOTE -> baseUrl + "/api/v1/public/files/" + fileId + "/promote?access_token=" + encodedToken;
            case DELETE -> baseUrl + "/api/v1/public/files/" + fileId + "?access_token=" + encodedToken;
            case PREVIEW -> baseUrl + "/api/v1/public/files/" + fileId + "/view?access_token=" + encodedToken;
            case DOWNLOAD -> baseUrl + "/api/v1/public/files/" + fileId + "/download?access_token=" + encodedToken;
        };
    }

    private RecordAttachmentAccess attachmentAccess(String mode, FileTransferAccess access) {
        return new RecordAttachmentAccess(mode, access.fileId(), access.accessToken(), access.url(),
                access.expiresAt().toString(), Map.of());
    }

    private String fileServerPurpose(FileTransferOperation operation) {
        return switch (operation) {
            case UPLOAD -> "upload";
            case METADATA -> "metadata";
            case PROMOTE -> "promote";
            case DELETE -> "delete";
            case PREVIEW -> "viewer";
            case DOWNLOAD -> "download";
        };
    }

    private String requireFileId(RecordAttachment attachment) {
        return requireText(attachment == null ? null : attachment.getFileId(), "attachment.fileId");
    }

    private String resolveStorageScopeId(CurrentUser user) {
        if (user.tenantId() != null) {
            return user.tenantId();
        }
        if (user.system()) {
            return requireText(properties.getSystemScopeId(),
                    "muyun.file-transfer.muyun-fileserver.system-scope-id");
        }
        throw new PlatformException("file transfer requires an authenticated tenant user");
    }

    private Duration requirePositive(Duration value, FileTransferOperation operation) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("file transfer " + operation + " token TTL must be positive");
        }
        return value;
    }

    private void validateConfiguration() {
        URI baseUrl = properties.getBaseUrl();
        if (baseUrl == null || !baseUrl.isAbsolute()
                || (!"http".equalsIgnoreCase(baseUrl.getScheme()) && !"https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalStateException("muyun.file-transfer.muyun-fileserver.base-url must be an absolute HTTP URL");
        }
        requireText(properties.getIssuer(), "muyun.file-transfer.muyun-fileserver.issuer");
        requireText(properties.getSecret(), "muyun.file-transfer.muyun-fileserver.secret");
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value.trim();
    }
}
