package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadFile;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceFieldPolicy;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadRequest;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceReadRequest;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccess;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccessService;
import net.ximatai.muyun.spring.platform.attachment.FileTransferOperation;
import net.ximatai.muyun.spring.platform.attachment.ManagedFileAsset;
import net.ximatai.muyun.spring.platform.attachment.ManagedFileAssetService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Standard, policy-gated ticket endpoint for form file-reference fields. */
@RestController
@RequestMapping("/{moduleAlias:[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+}/file-transfer")
public class FileReferenceUploadTicketWebController {
    private final List<FileReferenceFieldPolicy> policies;
    private final FileTransferAccessService transferAccessService;
    private final ManagedFileAssetService managedFileAssetService;
    private final PlatformModuleRuntimeContextService runtimeContextService;
    private final ObjectMapper objectMapper;

    public FileReferenceUploadTicketWebController(ObjectProvider<FileReferenceFieldPolicy> policies,
                                                   ObjectProvider<FileTransferAccessService> transferAccessService,
                                                   PlatformModuleRuntimeContextService runtimeContextService) {
        this(policies, transferAccessService, null, runtimeContextService, new ObjectMapper());
    }

    @Autowired
    public FileReferenceUploadTicketWebController(ObjectProvider<FileReferenceFieldPolicy> policies,
                                                   ObjectProvider<FileTransferAccessService> transferAccessService,
                                                   ObjectProvider<ManagedFileAssetService> managedFileAssetService,
                                                   PlatformModuleRuntimeContextService runtimeContextService,
                                                   ObjectMapper objectMapper) {
        this.policies = policies.orderedStream().toList();
        this.transferAccessService = transferAccessService.getIfAvailable();
        this.managedFileAssetService = managedFileAssetService == null ? null : managedFileAssetService.getIfAvailable();
        this.runtimeContextService = runtimeContextService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/upload-ticket")
    public FileTransferAccess uploadTicket(@PathVariable String moduleAlias,
                                           @RequestBody FileReferenceUploadTicketRequest body) {
        if (transferAccessService == null) {
            throw new PlatformConfigurationException("file transfer is not configured for this application");
        }
        FileReferenceUploadRequest request = body.toPolicyRequest(moduleAlias);
        ResolvedFileReferenceFieldDescriptor reference = requireReference(request);
        if (reference.storagePolicy() != FileReferenceStoragePolicy.MUYUN_FILE_SERVER) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "database-inline file references use the inline upload transport");
        }
        authorize(request);
        return transferAccessService.issueUploadAccess();
    }

    @PostMapping("/preview-ticket")
    public FileTransferAccess previewTicket(@PathVariable String moduleAlias,
                                            @RequestBody FileReferenceAccessTicketRequest body) {
        return readTicket(body.toPolicyRequest(moduleAlias, FileTransferOperation.PREVIEW));
    }

    @PostMapping("/download-ticket")
    public FileTransferAccess downloadTicket(@PathVariable String moduleAlias,
                                             @RequestBody FileReferenceAccessTicketRequest body) {
        return readTicket(body.toPolicyRequest(moduleAlias, FileTransferOperation.DOWNLOAD));
    }

    /**
     * DATABASE_INLINE counterpart of a FileServer upload: the browser receives a stable asset id,
     * then places only that id on its ordinary CRUD draft.
     */
    @PostMapping(value = "/inline-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Object> inlineUpload(@PathVariable String moduleAlias,
                                             @RequestPart("request") String rawRequest,
                                             @RequestPart("file") MultipartFile file) {
        FileReferenceUploadTicketRequest body = parse(rawRequest);
        FileReferenceUploadFile facts = new FileReferenceUploadFile(
                file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename(),
                file.getContentType(), file.getSize());
        FileReferenceUploadRequest request = new FileReferenceUploadRequest(moduleAlias, body.relationCode(),
                body.fieldName(), body.draft(), facts, body.intent());
        ResolvedFileReferenceFieldDescriptor reference = requireReference(request);
        if (reference.storagePolicy() != FileReferenceStoragePolicy.DATABASE_INLINE) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "file-server file references use the upload ticket transport");
        }
        FileReferenceFieldPolicy policy = authorize(request);
        validateInlineUpload(reference, facts);
        if (managedFileAssetService == null) {
            throw new PlatformConfigurationException("managed inline file assets are not configured for this application");
        }
        String ownerTenantId = policy.inlineAssetOwnerTenantId(request);
        if (ownerTenantId == null || ownerTenantId.isBlank()) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "database-inline file upload requires an owning tenant");
        }
        try {
            ManagedFileAsset asset = managedFileAssetService.createInline(ownerTenantId, facts.name(), facts.mediaType(),
                    file.getBytes());
            return Map.of("items", List.of(Map.of("id", asset.getId())));
        } catch (java.io.IOException exception) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "cannot read database-inline upload content", exception);
        }
    }

    private FileTransferAccess readTicket(FileReferenceReadRequest request) {
        ResolvedFileReferenceFieldDescriptor reference = requireReference(request.moduleAlias(), request.relationCode(),
                request.fieldName());
        FileReferenceFieldPolicy policy = authorizeRead(request);
        if (reference.storagePolicy() == FileReferenceStoragePolicy.DATABASE_INLINE) {
            if (managedFileAssetService == null) {
                throw new PlatformConfigurationException("managed inline file assets are not configured for this application");
            }
            String ownerTenantId = policy.inlineAssetOwnerTenantId(request);
            String content = managedFileAssetService.readInlineContent(ownerTenantId, request.fileId());
            return new FileTransferAccess(request.operation(), request.fileId(), null, content, null);
        }
        if (transferAccessService == null) {
            throw new PlatformConfigurationException("file transfer is not configured for this application");
        }
        return request.operation() == FileTransferOperation.PREVIEW
                ? transferAccessService.issuePreviewAccess(request.fileId())
                : transferAccessService.issueDownloadAccess(request.fileId());
    }

    private ResolvedFileReferenceFieldDescriptor requireReference(FileReferenceUploadRequest request) {
        return requireReference(request.moduleAlias(), request.relationCode(), request.fieldName());
    }

    private ResolvedFileReferenceFieldDescriptor requireReference(String moduleAlias, String relationCode, String fieldName) {
        ResolvedFileReferenceFieldDescriptor reference = runtimeContextService.fileReference(moduleAlias, relationCode, fieldName);
        if (reference == null) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "file-reference field is not declared by module runtime: " + moduleAlias + "." + fieldName);
        }
        return reference;
    }

    private FileReferenceFieldPolicy authorize(FileReferenceUploadRequest request) {
        FileReferenceFieldPolicy policy = requirePolicy(request.moduleAlias(), request.relationCode(), request.fieldName());
        policy.authorizeUpload(request);
        return policy;
    }

    private FileReferenceFieldPolicy authorizeRead(FileReferenceReadRequest request) {
        FileReferenceFieldPolicy policy = requirePolicy(request.moduleAlias(), request.relationCode(), request.fieldName());
        if (!policy.readAvailable()) {
            throw new PlatformConfigurationException("file-reference read access is not configured for "
                    + request.moduleAlias() + "." + request.fieldName());
        }
        policy.authorizeRead(request);
        return policy;
    }

    private FileReferenceFieldPolicy requirePolicy(String moduleAlias, String relationCode, String fieldName) {
        List<FileReferenceFieldPolicy> matching = policies.stream().filter(candidate -> candidate.supportsField(
                moduleAlias, relationCode, fieldName)).toList();
        if (matching.size() != 1) {
            throw new PlatformConfigurationException((matching.isEmpty() ? "no" : "multiple")
                    + " file-reference field policies are configured for " + moduleAlias + "." + fieldName);
        }
        return matching.getFirst();
    }

    private FileReferenceUploadTicketRequest parse(String rawRequest) {
        try {
            return objectMapper.readValue(rawRequest, FileReferenceUploadTicketRequest.class);
        } catch (JsonProcessingException exception) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "inline upload request is invalid", exception);
        }
    }

    private void validateInlineUpload(ResolvedFileReferenceFieldDescriptor reference, FileReferenceUploadFile file) {
        if (reference.maxFileSizeBytes() != null && file.sizeBytes() > reference.maxFileSizeBytes()) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "file reference exceeds maximum file size");
        }
        if (!reference.allowedMediaTypes().isEmpty() && (file.mediaType() == null || reference.allowedMediaTypes().stream()
                .noneMatch(allowed -> mediaTypeMatches(allowed, file.mediaType())))) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "file reference media type is not allowed: " + file.mediaType());
        }
    }

    private boolean mediaTypeMatches(String allowed, String actual) {
        if (allowed.equalsIgnoreCase(actual)) return true;
        String normalized = allowed == null ? "" : allowed.trim().toLowerCase(java.util.Locale.ROOT);
        String actualNormalized = actual == null ? "" : actual.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith("/*") && actualNormalized.startsWith(normalized.substring(0, normalized.length() - 1));
    }
}
