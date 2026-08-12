package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadFile;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadIntent;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceFieldPolicy;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadRequest;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccess;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccessService;
import net.ximatai.muyun.spring.platform.attachment.FileTransferOperation;
import net.ximatai.muyun.spring.platform.attachment.ManagedFileAsset;
import net.ximatai.muyun.spring.platform.attachment.ManagedFileAssetService;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class FileReferenceUploadTicketWebControllerTest {
    @Test
    void storesDatabaseInlineUploadsBeforeTheLaterOrdinaryCrudBinding() {
        FileReferenceFieldPolicy policy = new FileReferenceFieldPolicy() {
            @Override public boolean supportsField(String moduleAlias, String relationCode, String fieldName) { return true; }
            @Override public void authorizeUpload(FileReferenceUploadRequest request) { }
            @Override public boolean readAvailable() { return true; }
            @Override public void authorizeRead(net.ximatai.muyun.spring.platform.attachment.FileReferenceReadRequest request) { }
            @Override public String inlineAssetOwnerTenantId(FileReferenceUploadRequest request) { return "tenant-a"; }
            @Override public String inlineAssetOwnerTenantId(net.ximatai.muyun.spring.platform.attachment.FileReferenceReadRequest request) { return "tenant-a"; }
        };
        ManagedFileAssetService assets = mock(ManagedFileAssetService.class);
        ManagedFileAsset asset = new ManagedFileAsset();
        asset.setId("asset-1");
        when(assets.createInline("tenant-a", "logo.png", "image/png", new byte[] {1, 2, 3})).thenReturn(asset);
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("policy", policy);
        factory.registerSingleton("assets", assets);
        PlatformModuleRuntimeContextService context = mock(PlatformModuleRuntimeContextService.class);
        when(context.fileReference("iam.tenant", null, "lightLogoAssetId")).thenReturn(new ResolvedFileReferenceFieldDescriptor(
                ViewFieldRef.main("lightLogoAssetId"), java.util.Set.of("image/png"), 1024L, 1,
                FileReferenceStoragePolicy.DATABASE_INLINE, true));
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceFieldPolicy.class), factory.getBeanProvider(FileTransferAccessService.class),
                factory.getBeanProvider(ManagedFileAssetService.class), context, new ObjectMapper());

        assertThat(controller.inlineUpload("iam.tenant", """
                {"fieldName":"lightLogoAssetId","draft":{"alias":"tenant-a"},"intent":"CREATE"}
                """, new MockMultipartFile("file", "logo.png", "image/png", new byte[] {1, 2, 3})))
                .isEqualTo(Map.of("items", List.of(Map.of("id", "asset-1"))));
        verify(assets).createInline("tenant-a", "logo.png", "image/png", new byte[] {1, 2, 3});
    }

    @Test
    void preservesNullStandardDraftFieldsForInlineUploadAdmission() {
        FileReferenceFieldPolicy policy = new FileReferenceFieldPolicy() {
            @Override public boolean supportsField(String moduleAlias, String relationCode, String fieldName) { return true; }
            @Override public void authorizeUpload(FileReferenceUploadRequest request) {
                assertThat(request.draft()).containsEntry("tenantId", null);
            }
            @Override public String inlineAssetOwnerTenantId(FileReferenceUploadRequest request) { return "tenant-a"; }
        };
        ManagedFileAssetService assets = mock(ManagedFileAssetService.class);
        ManagedFileAsset asset = new ManagedFileAsset();
        asset.setId("asset-1");
        when(assets.createInline("tenant-a", "logo.png", "image/png", new byte[] {1})).thenReturn(asset);
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("policy", policy);
        factory.registerSingleton("assets", assets);
        PlatformModuleRuntimeContextService context = mock(PlatformModuleRuntimeContextService.class);
        when(context.fileReference("iam.tenant", null, "lightLogoAssetId")).thenReturn(new ResolvedFileReferenceFieldDescriptor(
                ViewFieldRef.main("lightLogoAssetId"), java.util.Set.of("image/png"), 1024L, 1,
                FileReferenceStoragePolicy.DATABASE_INLINE, true));
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceFieldPolicy.class), factory.getBeanProvider(FileTransferAccessService.class),
                factory.getBeanProvider(ManagedFileAssetService.class), context, new ObjectMapper());

        assertThat(controller.inlineUpload("iam.tenant", """
                {"fieldName":"lightLogoAssetId","draft":{"alias":"tenant-a","tenantId":null},"intent":"CREATE"}
                """, new MockMultipartFile("file", "logo.png", "image/png", new byte[] {1})))
                .isEqualTo(Map.of("items", List.of(Map.of("id", "asset-1"))));
    }

    @Test
    void issuesAnInlinePreviewTargetThroughTheSameDeclaredFieldPolicy() {
        FileReferenceFieldPolicy policy = new FileReferenceFieldPolicy() {
            @Override public boolean supportsField(String moduleAlias, String relationCode, String fieldName) { return true; }
            @Override public void authorizeUpload(FileReferenceUploadRequest request) { }
            @Override public boolean readAvailable() { return true; }
            @Override public void authorizeRead(net.ximatai.muyun.spring.platform.attachment.FileReferenceReadRequest request) { }
            @Override public String inlineAssetOwnerTenantId(FileReferenceUploadRequest request) { return "tenant-a"; }
            @Override public String inlineAssetOwnerTenantId(net.ximatai.muyun.spring.platform.attachment.FileReferenceReadRequest request) { return "tenant-a"; }
        };
        ManagedFileAssetService assets = mock(ManagedFileAssetService.class);
        when(assets.readInlineContent("tenant-a", "asset-1")).thenReturn("data:image/png;base64,AQ==");
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("policy", policy);
        factory.registerSingleton("assets", assets);
        PlatformModuleRuntimeContextService context = mock(PlatformModuleRuntimeContextService.class);
        when(context.fileReference("iam.tenant", null, "lightLogoAssetId")).thenReturn(new ResolvedFileReferenceFieldDescriptor(
                ViewFieldRef.main("lightLogoAssetId"), java.util.Set.of("image/png"), 1024L, 1,
                FileReferenceStoragePolicy.DATABASE_INLINE, true));
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceFieldPolicy.class), factory.getBeanProvider(FileTransferAccessService.class),
                factory.getBeanProvider(ManagedFileAssetService.class), context, new ObjectMapper());

        FileTransferAccess ticket = controller.previewTicket("iam.tenant", new FileReferenceAccessTicketRequest(null,
                "lightLogoAssetId", Map.of("alias", "tenant-a"), "asset-1"));

        assertThat(ticket.operation()).isEqualTo(FileTransferOperation.PREVIEW);
        assertThat(ticket.url()).isEqualTo("data:image/png;base64,AQ==");
        verify(assets).readInlineContent("tenant-a", "asset-1");
    }

    @Test
    void delegatesOnlyTheExplicitPolicyAuthorizedRequestToFileTransfer() {
        AtomicReference<FileReferenceUploadRequest> authorized = new AtomicReference<>();
        FileReferenceFieldPolicy policy = new FileReferenceFieldPolicy() {
            @Override public boolean supportsField(String moduleAlias, String relationCode, String fieldName) {
                return moduleAlias.equals("sales.contract") && fieldName.equals("fileId");
            }
            @Override public void authorizeUpload(FileReferenceUploadRequest request) { authorized.set(request); }
        };
        FileTransferAccess expected = new FileTransferAccess(FileTransferOperation.UPLOAD, null, "token",
                "https://files.example/upload", Instant.parse("2026-01-01T00:00:00Z"));
        FileTransferAccessService transfer = new FileTransferAccessService() {
            @Override public FileTransferAccess issueUploadAccess() { return expected; }
            @Override public FileTransferAccess issueMetadataAccess(String fileId) { throw new UnsupportedOperationException(); }
            @Override public FileTransferAccess issuePromoteAccess(String fileId) { throw new UnsupportedOperationException(); }
            @Override public FileTransferAccess issuePreviewAccess(String fileId) { throw new UnsupportedOperationException(); }
            @Override public FileTransferAccess issueDownloadAccess(String fileId) { throw new UnsupportedOperationException(); }
        };
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("policy", policy);
        factory.registerSingleton("transfer", transfer);
        PlatformModuleRuntimeContextService runtimeContextService = mock(PlatformModuleRuntimeContextService.class);
        when(runtimeContextService.fileReference("sales.contract", null, "fileId")).thenReturn(fileReference("fileId"));
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceFieldPolicy.class), factory.getBeanProvider(FileTransferAccessService.class),
                runtimeContextService);

        assertThat(controller.uploadTicket("sales.contract", new FileReferenceUploadTicketRequest(null, "fileId",
                Map.of("directoryId", "directory-1"), new FileReferenceUploadFile("a.pdf", "application/pdf", 42),
                FileReferenceUploadIntent.CREATE))).isEqualTo(expected);
        assertThat(authorized.get()).isEqualTo(new FileReferenceUploadRequest("sales.contract", null, "fileId",
                Map.of("directoryId", "directory-1"), new FileReferenceUploadFile("a.pdf", "application/pdf", 42),
                FileReferenceUploadIntent.CREATE));
    }

    @Test
    void preservesAppendAsAnAuthorizationFact() {
        AtomicReference<FileReferenceUploadIntent> authorizedIntent = new AtomicReference<>();
        FileReferenceFieldPolicy policy = new FileReferenceFieldPolicy() {
            @Override public boolean supportsField(String moduleAlias, String relationCode, String fieldName) { return true; }
            @Override public void authorizeUpload(FileReferenceUploadRequest request) { authorizedIntent.set(request.intent()); }
        };
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("policy", policy);
        factory.registerSingleton("transfer", transferAccessService());
        PlatformModuleRuntimeContextService context = mock(PlatformModuleRuntimeContextService.class);
        when(context.fileReference("sales.contract", null, "fileIds")).thenReturn(fileReference("fileIds"));
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceFieldPolicy.class), factory.getBeanProvider(FileTransferAccessService.class), context);

        controller.uploadTicket("sales.contract", new FileReferenceUploadTicketRequest(null, "fileIds", Map.of(),
                new FileReferenceUploadFile("a.pdf", "application/pdf", 42), FileReferenceUploadIntent.APPEND));

        assertThat(authorizedIntent.get()).isEqualTo(FileReferenceUploadIntent.APPEND);
    }

    @Test
    void rejectsFieldsThatTheModuleRuntimeDoesNotDeclareAsFileReferences() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("transfer", transferAccessService());
        PlatformModuleRuntimeContextService runtimeContextService = mock(PlatformModuleRuntimeContextService.class);
        when(runtimeContextService.fileReference("sales.contract", null, "otherField")).thenReturn(null);
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceFieldPolicy.class), factory.getBeanProvider(FileTransferAccessService.class),
                runtimeContextService);

        assertThatThrownBy(() -> controller.uploadTicket("sales.contract", request("otherField")))
                .isInstanceOfSatisfying(PlatformException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(exception.httpStatus()).isEqualTo(400);
                });
    }

    @Test
    void reportsMissingPolicyAndTransferConfigurationInsteadOfFallingThroughToARouteMiss() {
        DefaultListableBeanFactory policyOnly = new DefaultListableBeanFactory();
        policyOnly.registerSingleton("transfer", transferAccessService());
        PlatformModuleRuntimeContextService context = mock(PlatformModuleRuntimeContextService.class);
        when(context.fileReference("sales.contract", null, "fileId")).thenReturn(fileReference("fileId"));
        FileReferenceUploadTicketWebController noPolicy = new FileReferenceUploadTicketWebController(
                policyOnly.getBeanProvider(FileReferenceFieldPolicy.class), policyOnly.getBeanProvider(FileTransferAccessService.class), context);
        assertThatThrownBy(() -> noPolicy.uploadTicket("sales.contract", request("fileId")))
                .isInstanceOfSatisfying(PlatformException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CONFIG_MISSING");
                    assertThat(exception.httpStatus()).isEqualTo(409);
                });

        DefaultListableBeanFactory noTransfer = new DefaultListableBeanFactory();
        FileReferenceUploadTicketWebController missingTransfer = new FileReferenceUploadTicketWebController(
                noTransfer.getBeanProvider(FileReferenceFieldPolicy.class), noTransfer.getBeanProvider(FileTransferAccessService.class), context);
        assertThatThrownBy(() -> missingTransfer.uploadTicket("sales.contract", request("fileId")))
                .isInstanceOfSatisfying(PlatformException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CONFIG_MISSING");
                    assertThat(exception.httpStatus()).isEqualTo(409);
                });
    }

    @Test
    void rejectsAmbiguousPoliciesInsteadOfSelectingByBeanOrder() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("first", policyFor("sales.contract", "fileId"));
        factory.registerSingleton("second", policyFor("sales.contract", "fileId"));
        factory.registerSingleton("transfer", transferAccessService());
        PlatformModuleRuntimeContextService context = mock(PlatformModuleRuntimeContextService.class);
        when(context.fileReference("sales.contract", null, "fileId")).thenReturn(fileReference("fileId"));
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceFieldPolicy.class), factory.getBeanProvider(FileTransferAccessService.class), context);

        assertThatThrownBy(() -> controller.uploadTicket("sales.contract", request("fileId")))
                .isInstanceOfSatisfying(PlatformException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CONFIG_MISSING");
                    assertThat(exception.httpStatus()).isEqualTo(409);
                });
    }

    private static FileReferenceFieldPolicy policyFor(String moduleAlias, String fieldName) {
        return new FileReferenceFieldPolicy() {
            @Override public boolean supportsField(String candidateModuleAlias, String relationCode, String candidateFieldName) {
                return moduleAlias.equals(candidateModuleAlias) && fieldName.equals(candidateFieldName);
            }

            @Override public void authorizeUpload(FileReferenceUploadRequest request) {
                // Field matching is the sole resolver; this policy permits the test request.
            }
        };
    }

    private static FileReferenceUploadTicketRequest request(String fieldName) {
        return new FileReferenceUploadTicketRequest(null, fieldName, Map.of(),
                new FileReferenceUploadFile("a.pdf", "application/pdf", 42), FileReferenceUploadIntent.CREATE);
    }

    private static ResolvedFileReferenceFieldDescriptor fileReference(String fieldName) {
        return new ResolvedFileReferenceFieldDescriptor(ViewFieldRef.main(fieldName), java.util.Set.of(), null, 1,
                FileReferenceStoragePolicy.MUYUN_FILE_SERVER, true);
    }

    private static FileTransferAccessService transferAccessService() {
        return new FileTransferAccessService() {
            @Override public FileTransferAccess issueUploadAccess() {
                return new FileTransferAccess(FileTransferOperation.UPLOAD, null, "token", "https://files.example/upload", Instant.now());
            }
            @Override public FileTransferAccess issueMetadataAccess(String fileId) { throw new UnsupportedOperationException(); }
            @Override public FileTransferAccess issuePromoteAccess(String fileId) { throw new UnsupportedOperationException(); }
            @Override public FileTransferAccess issuePreviewAccess(String fileId) { throw new UnsupportedOperationException(); }
            @Override public FileTransferAccess issueDownloadAccess(String fileId) { throw new UnsupportedOperationException(); }
        };
    }
}
