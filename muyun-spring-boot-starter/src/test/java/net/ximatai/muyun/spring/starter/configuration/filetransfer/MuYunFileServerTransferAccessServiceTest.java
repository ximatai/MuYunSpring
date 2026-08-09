package net.ximatai.muyun.spring.starter.configuration.filetransfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccess;
import net.ximatai.muyun.spring.platform.attachment.FileTransferOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MuYunFileServerTransferAccessServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearCurrentUser() {
        CurrentUserContext.clear();
    }

    @Test
    void shouldIssueTenantBoundUrlsForEverySupportedTransferOperation() throws Exception {
        MuYunFileServerTransferAccessService service = service();
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "operator", "tenant-a"))) {
            FileTransferAccess upload = service.issueUploadAccess();
            FileTransferAccess metadata = service.issueMetadataAccess("01ARZ3NDEKTSV4RRFFQ69G5FAV");
            FileTransferAccess promote = service.issuePromoteAccess("01ARZ3NDEKTSV4RRFFQ69G5FAV");
            FileTransferAccess delete = service.issueDeleteAccess("01ARZ3NDEKTSV4RRFFQ69G5FAV");
            FileTransferAccess preview = service.issuePreviewAccess("01ARZ3NDEKTSV4RRFFQ69G5FAV");
            FileTransferAccess download = service.issueDownloadAccess("01ARZ3NDEKTSV4RRFFQ69G5FAV");

            assertThat(upload.operation()).isEqualTo(FileTransferOperation.UPLOAD);
            assertThat(upload.fileId()).isNull();
            assertThat(upload.url()).startsWith("http://files.example/api/v1/public/files?access_token=");
            assertThat(metadata.url()).contains("/01ARZ3NDEKTSV4RRFFQ69G5FAV?access_token=");
            assertThat(promote.url()).contains("/01ARZ3NDEKTSV4RRFFQ69G5FAV/promote?access_token=");
            assertThat(delete.operation()).isEqualTo(FileTransferOperation.DELETE);
            assertThat(delete.url()).contains("/01ARZ3NDEKTSV4RRFFQ69G5FAV?access_token=");
            assertThat(preview.url()).contains("/01ARZ3NDEKTSV4RRFFQ69G5FAV/view?access_token=");
            assertThat(download.url()).contains("/01ARZ3NDEKTSV4RRFFQ69G5FAV/download?access_token=");
            assertThat(payload(upload).path("purpose").asText()).isEqualTo("upload");
            assertThat(payload(metadata).path("purpose").asText()).isEqualTo("metadata");
            assertThat(payload(promote).path("purpose").asText()).isEqualTo("promote");
            assertThat(payload(delete).path("purpose").asText()).isEqualTo("delete");
            assertThat(payload(preview).path("purpose").asText()).isEqualTo("viewer");
            assertThat(payload(download).path("purpose").asText()).isEqualTo("download");
            assertThat(payload(download).path("tenant_id").asText()).isEqualTo("tenant-a");
            assertThat(payload(download).path("sub").asText()).isEqualTo("user-1");
        }
    }

    @Test
    void shouldIssueSystemWorkspaceTicketsOnlyForAnExplicitSystemScope() throws Exception {
        MuYunFileServerTransferAccessService service = service();
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin-1", "admin"))) {
            assertThatThrownBy(service::issueUploadAccess)
                    .hasMessageContaining("system-scope-id");
        }

        MuYunFileServerTransferProperties properties = properties();
        properties.setSystemScopeId("mr-system");
        service = new MuYunFileServerTransferAccessService(properties, objectMapper,
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin-1", "admin"))) {
            assertThat(payload(service.issueUploadAccess()).path("tenant_id").asText()).isEqualTo("mr-system");
        }
    }

    private MuYunFileServerTransferAccessService service() {
        return new MuYunFileServerTransferAccessService(properties(), objectMapper,
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));
    }

    private MuYunFileServerTransferProperties properties() {
        MuYunFileServerTransferProperties properties = new MuYunFileServerTransferProperties();
        properties.setBaseUrl(java.net.URI.create("http://files.example/"));
        properties.setIssuer("muyun-platform");
        properties.setSecret("test-signing-secret");
        return properties;
    }

    private JsonNode payload(FileTransferAccess access) throws Exception {
        String encodedPayload = access.accessToken().substring(0, access.accessToken().indexOf('.'));
        return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8));
    }
}
