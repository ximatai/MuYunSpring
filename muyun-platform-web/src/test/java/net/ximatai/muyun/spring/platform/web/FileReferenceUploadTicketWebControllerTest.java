package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadFile;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadIntent;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadPolicy;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadRequest;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccess;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccessService;
import net.ximatai.muyun.spring.platform.attachment.FileTransferOperation;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileReferenceUploadTicketWebControllerTest {
    @Test
    void delegatesOnlyTheExplicitPolicyAuthorizedRequestToFileTransfer() {
        AtomicReference<FileReferenceUploadRequest> authorized = new AtomicReference<>();
        FileReferenceUploadPolicy policy = new FileReferenceUploadPolicy() {
            @Override public boolean supportsField(String moduleAlias, String relationCode, String fieldName) {
                return moduleAlias.equals("sales.contract") && fieldName.equals("fileId");
            }
            @Override public void authorize(FileReferenceUploadRequest request) { authorized.set(request); }
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
        when(runtimeContextService.declaresFileReference("sales.contract", null, "fileId")).thenReturn(true);
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceUploadPolicy.class), factory.getBeanProvider(FileTransferAccessService.class),
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
        FileReferenceUploadPolicy policy = new FileReferenceUploadPolicy() {
            @Override public boolean supportsField(String moduleAlias, String relationCode, String fieldName) { return true; }
            @Override public void authorize(FileReferenceUploadRequest request) { authorizedIntent.set(request.intent()); }
        };
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("policy", policy);
        factory.registerSingleton("transfer", transferAccessService());
        PlatformModuleRuntimeContextService context = mock(PlatformModuleRuntimeContextService.class);
        when(context.declaresFileReference("sales.contract", null, "fileIds")).thenReturn(true);
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceUploadPolicy.class), factory.getBeanProvider(FileTransferAccessService.class), context);

        controller.uploadTicket("sales.contract", new FileReferenceUploadTicketRequest(null, "fileIds", Map.of(),
                new FileReferenceUploadFile("a.pdf", "application/pdf", 42), FileReferenceUploadIntent.APPEND));

        assertThat(authorizedIntent.get()).isEqualTo(FileReferenceUploadIntent.APPEND);
    }

    @Test
    void rejectsFieldsThatTheModuleRuntimeDoesNotDeclareAsFileReferences() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("transfer", transferAccessService());
        PlatformModuleRuntimeContextService runtimeContextService = mock(PlatformModuleRuntimeContextService.class);
        when(runtimeContextService.declaresFileReference("sales.contract", null, "otherField")).thenReturn(false);
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceUploadPolicy.class), factory.getBeanProvider(FileTransferAccessService.class),
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
        when(context.declaresFileReference("sales.contract", null, "fileId")).thenReturn(true);
        FileReferenceUploadTicketWebController noPolicy = new FileReferenceUploadTicketWebController(
                policyOnly.getBeanProvider(FileReferenceUploadPolicy.class), policyOnly.getBeanProvider(FileTransferAccessService.class), context);
        assertThatThrownBy(() -> noPolicy.uploadTicket("sales.contract", request("fileId")))
                .isInstanceOfSatisfying(PlatformException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CONFIG_MISSING");
                    assertThat(exception.httpStatus()).isEqualTo(409);
                });

        DefaultListableBeanFactory noTransfer = new DefaultListableBeanFactory();
        FileReferenceUploadTicketWebController missingTransfer = new FileReferenceUploadTicketWebController(
                noTransfer.getBeanProvider(FileReferenceUploadPolicy.class), noTransfer.getBeanProvider(FileTransferAccessService.class), context);
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
        when(context.declaresFileReference("sales.contract", null, "fileId")).thenReturn(true);
        FileReferenceUploadTicketWebController controller = new FileReferenceUploadTicketWebController(
                factory.getBeanProvider(FileReferenceUploadPolicy.class), factory.getBeanProvider(FileTransferAccessService.class), context);

        assertThatThrownBy(() -> controller.uploadTicket("sales.contract", request("fileId")))
                .isInstanceOfSatisfying(PlatformException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CONFIG_MISSING");
                    assertThat(exception.httpStatus()).isEqualTo(409);
                });
    }

    private static FileReferenceUploadPolicy policyFor(String moduleAlias, String fieldName) {
        return new FileReferenceUploadPolicy() {
            @Override public boolean supportsField(String candidateModuleAlias, String relationCode, String candidateFieldName) {
                return moduleAlias.equals(candidateModuleAlias) && fieldName.equals(candidateFieldName);
            }

            @Override public void authorize(FileReferenceUploadRequest request) {
                // Field matching is the sole resolver; this policy permits the test request.
            }
        };
    }

    private static FileReferenceUploadTicketRequest request(String fieldName) {
        return new FileReferenceUploadTicketRequest(null, fieldName, Map.of(),
                new FileReferenceUploadFile("a.pdf", "application/pdf", 42), FileReferenceUploadIntent.CREATE);
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
