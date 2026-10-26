package net.ximatai.muyun.spring.starter.configuration.filetransfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccess;
import net.ximatai.muyun.spring.platform.attachment.FileTransferClient;
import net.ximatai.muyun.spring.platform.attachment.FileTransferFileMetadata;
import net.ximatai.muyun.spring.platform.attachment.FileTransferOperation;
import net.ximatai.muyun.spring.platform.attachment.FileTransferUploadFacts;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Official backend client for MuYunFileServer's public, token-protected API.
 * Browser URLs are never reused here; each server call receives its own
 * least-privilege credential from {@link MuYunFileServerTransferAccessService}.
 */
final class MuYunFileServerTransferClient implements FileTransferClient {
    private final MuYunFileServerTransferAccessService accessService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpClient contentHttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    MuYunFileServerTransferClient(MuYunFileServerTransferAccessService accessService,
                                  RestClient restClient,
                                  ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public FileTransferFileMetadata readMetadata(String fileId) {
        return metadata(accessService.issueMetadataAccess(fileId), FileTransferOperation.METADATA);
    }

    @Override
    public FileTransferUploadFacts readUploadFacts(String fileId) {
        JsonNode source = metadataNode(accessService.issueMetadataAccess(fileId), FileTransferOperation.METADATA);
        String uploader = text(source, "uploadedBy");
        String tenant = text(source, "tenantId");
        if (!source.path("uploadedBy").isTextual() || !source.path("tenantId").isTextual()
                || uploader == null || uploader.isBlank() || tenant == null || tenant.isBlank()) {
            throw new PlatformException("file server returned no authoritative upload identity");
        }
        return new FileTransferUploadFacts(toMetadata(source, fileId), uploader, tenant);
    }

    @Override
    public FileTransferFileMetadata promote(String fileId) {
        return metadata(accessService.issuePromoteAccess(fileId), FileTransferOperation.PROMOTE);
    }

    @Override
    public InputStream openContent(String fileId) {
        FileTransferAccess access = accessService.issueDownloadAccess(fileId);
        if (access.operation() != FileTransferOperation.DOWNLOAD) {
            throw new IllegalStateException("unexpected file transfer access operation: " + access.operation());
        }
        try {
            HttpResponse<InputStream> response = contentHttpClient.send(
                    HttpRequest.newBuilder(URI.create(access.url())).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new PlatformException("file server download request failed for file: " + access.fileId()
                        + ", HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlatformException("file server download request was interrupted for file: " + access.fileId());
        } catch (java.io.IOException exception) {
            throw new PlatformException("file server download request failed for file: " + access.fileId());
        }
    }

    @Override
    public void delete(String fileId) {
        FileTransferAccess access = accessService.issueDeleteAccess(fileId);
        if (access.operation() != FileTransferOperation.DELETE) {
            throw new IllegalStateException("unexpected file transfer access operation: " + access.operation());
        }
        try {
            String responseBody = restClient.delete().uri(access.url()).retrieve().body(String.class);
            JsonNode data = requiredData(objectMapper.readTree(responseBody));
            if (!access.fileId().equals(text(data, "fileId")) || !"DELETED".equals(text(data, "status"))) {
                throw new PlatformException("file server delete response does not confirm requested file");
            }
        } catch (RestClientException | java.io.IOException exception) {
            throw new PlatformException("file server delete request failed for file: " + access.fileId());
        }
    }

    private FileTransferFileMetadata metadata(FileTransferAccess access, FileTransferOperation expectedOperation) {
        return toMetadata(metadataNode(access, expectedOperation), access.fileId());
    }

    private JsonNode metadataNode(FileTransferAccess access, FileTransferOperation expectedOperation) {
        if (access.operation() != expectedOperation) {
            throw new IllegalStateException("unexpected file transfer access operation: " + access.operation());
        }
        try {
            String responseBody = switch (expectedOperation) {
                case METADATA -> restClient.get().uri(access.url()).retrieve().body(String.class);
                case PROMOTE -> restClient.post().uri(access.url()).retrieve().body(String.class);
                default -> throw new IllegalArgumentException("unsupported metadata operation: " + expectedOperation);
            };
            JsonNode response = objectMapper.readTree(responseBody);
            JsonNode data = requiredData(response);
            JsonNode metadata = expectedOperation == FileTransferOperation.PROMOTE
                    ? requiredFirstItem(data)
                    : data;
            return metadata;
        } catch (RestClientException | java.io.IOException exception) {
            throw new PlatformException("file server " + expectedOperation.name().toLowerCase()
                    + " request failed for file: " + access.fileId());
        }
    }

    private JsonNode requiredData(JsonNode response) {
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || data.isNull()) {
            throw new PlatformException("file server returned no data");
        }
        return data;
    }

    private JsonNode requiredFirstItem(JsonNode response) {
        JsonNode items = response.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new PlatformException("file server promote response contains no file metadata");
        }
        return items.get(0);
    }

    private FileTransferFileMetadata toMetadata(JsonNode source, String expectedFileId) {
        String fileId = text(source, "id");
        if (!expectedFileId.equals(fileId)) {
            throw new PlatformException("file server response does not match requested file");
        }
        long sizeBytes = source.path("sizeBytes").asLong(-1);
        if (sizeBytes < 0) {
            throw new PlatformException("file server returned invalid file size for: " + fileId);
        }
        Integer imageWidth = imageDimension(source, "imageWidth");
        Integer imageHeight = imageDimension(source, "imageHeight");
        if ((imageWidth == null) != (imageHeight == null)) {
            throw new PlatformException("file server returned incomplete image dimensions for: " + fileId);
        }
        JsonNode temporary = source.get("temporary");
        if (temporary == null || !temporary.isBoolean()) {
            throw new PlatformException("file server returned no reliable temporary state for: " + fileId);
        }
        return new FileTransferFileMetadata(fileId, text(source, "originalFilename"), text(source, "extension"),
                text(source, "mimeType"), sizeBytes, text(source, "sha256"), text(source, "status"),
                temporary.booleanValue(), instant(source, "uploadedAt"), imageWidth, imageHeight);
    }

    private Integer imageDimension(JsonNode source, String name) {
        JsonNode value = source.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new PlatformException("file server returned invalid image dimension: " + name);
        }
        return value.intValue();
    }

    private String text(JsonNode source, String field) {
        JsonNode value = source.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Instant instant(JsonNode source, String field) {
        String value = text(source, field);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
