package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileReferenceUploadServiceTest {
    @Test
    void pendingReadRequiresUploaderTenantModuleRelationFieldAndRecord() {
        var dao = new UploadDao();
        var service = new FileReferenceUploadService(dao, org.mockito.Mockito.mock(FileReferenceUploadClaimDao.class), new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(ManagedFileAssetService.class), new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(FileTransferClient.class), java.time.Clock.systemUTC());
        try (var tenant = TenantContext.use("tenant-a");
             var actor = CurrentUserContext.use(CurrentUser.tenantUser("uploader", "Uploader", "tenant-a"))) {
            service.record("tenant-a", new FileReferenceUploadRequest("sales.document", null, "fileId", Map.of("id", "record-1"),
                    new FileReferenceUploadFile("a.pdf", "application/pdf", 3), FileReferenceUploadIntent.CREATE), "file-1");
            var own = read("sales.document", null, "fileId", "record-1");
            assertThat(service.ownsPending("tenant-a", own)).isTrue();
            assertThat(service.ownsPending("tenant-b", own)).isFalse();
            assertThat(service.ownsPending("tenant-a", read("sales.other", null, "fileId", "record-1"))).isFalse();
            assertThat(service.ownsPending("tenant-a", read("sales.document", "items", "fileId", "record-1"))).isFalse();
            assertThat(service.ownsPending("tenant-a", read("sales.document", null, "otherFileId", "record-1"))).isFalse();
            assertThat(service.ownsPending("tenant-a", read("sales.document", null, "fileId", "record-2"))).isFalse();
            try (var other = CurrentUserContext.use(CurrentUser.tenantUser("other", "Other", "tenant-a"))) {
                assertThat(service.ownsPending("tenant-a", own)).isFalse();
            }

        }
    }

    @Test
    void aRecordWriteCannotTurnAnotherUsersInlineAssetIntoAReadableOwnReference() {
        var assets = org.mockito.Mockito.mock(ManagedFileAssetService.class);
        var asset = new ManagedFileAsset();
        asset.setId("foreign-file"); asset.setTenantId("tenant-a"); asset.setCreatedBy("other-user");
        org.mockito.Mockito.when(assets.requireOwned("tenant-a", "foreign-file")).thenReturn(asset);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("assets", assets);
        var service = new FileReferenceUploadService(new UploadDao(), org.mockito.Mockito.mock(FileReferenceUploadClaimDao.class), beans.getBeanProvider(ManagedFileAssetService.class), new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(FileTransferClient.class), java.time.Clock.systemUTC());
        var ability = new net.ximatai.muyun.spring.ability.AbstractAbilityService<Document>("sales.document", Document.class, new TestMemoryDao<>()) {};
        var incoming = new Document(); incoming.setId("own-record"); incoming.setTenantId("tenant-a"); incoming.fileId = "foreign-file";
        try (var tenant = TenantContext.use("tenant-a");
             var actor = CurrentUserContext.use(CurrentUser.tenantUser("uploader", "Uploader", "tenant-a"))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepareValues(ability, null, incoming))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            // Keeping an already-authorized reference does not require another upload.
            service.prepareValues(ability, incoming, incoming);
            // Trusted server-created assets remain usable by their creator.
            asset.setCreatedBy("uploader");
            service.prepareValues(ability, null, incoming);
        }
    }

    @Test
    void externalUploadMustMatchActorDestinationDraftAndRemainTemporaryThroughSaveAndRead() {
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var client = org.mockito.Mockito.mock(FileTransferClient.class);
        beans.addBean("client", client);
        var dao = new UploadDao();
        var service = new FileReferenceUploadService(dao, org.mockito.Mockito.mock(FileReferenceUploadClaimDao.class), beans.getBeanProvider(ManagedFileAssetService.class),
                beans.getBeanProvider(FileTransferClient.class), java.time.Clock.systemUTC());
        var ability = new net.ximatai.muyun.spring.ability.AbstractAbilityService<ExternalDocument>("sales.document", ExternalDocument.class, new TestMemoryDao<>()) {};
        var incoming = new ExternalDocument(); incoming.setId("record-1"); incoming.setTenantId("tenant-a"); incoming.fileId = "file-1";
        org.mockito.Mockito.when(client.readUploadFacts("file-1")).thenReturn(uploadFacts(true, "uploader"));
        try (var tenant = TenantContext.use("tenant-a");
             var actor = CurrentUserContext.use(CurrentUser.tenantUser("uploader", "Uploader", "tenant-a"))) {
            var ticket = service.registerTicket("tenant-a", uploadRequest("sales.document", null, "fileId", "record-1"), access());
            service.bindUploadedFile("sales.document", ticket, "file-1");
            service.prepareValues(ability, null, incoming);
            assertThat(service.ownsPending("tenant-a", read("sales.document", null, "fileId", "record-1"))).isTrue();
            incoming.setId("different-record");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepareValues(ability, null, incoming))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            incoming.setId("record-1");
            var wrongModule = new net.ximatai.muyun.spring.ability.AbstractAbilityService<ExternalDocument>("sales.other", ExternalDocument.class, new TestMemoryDao<>()) {};
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepareValues(wrongModule, null, incoming))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            try (var other = CurrentUserContext.use(CurrentUser.tenantUser("other", "Other", "tenant-a"))) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepareValues(ability, null, incoming))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
            // The same pending registration stops authorizing as soon as FileServer reports permanent.
            org.mockito.Mockito.when(client.readUploadFacts("file-1")).thenReturn(uploadFacts(false, "uploader"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepareValues(ability, null, incoming))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ownsPending("tenant-a", read("sales.document", null, "fileId", "record-1")))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            service.persisted(ability, incoming);
            assertThat(service.ownsPending("tenant-a", read("sales.document", null, "fileId", "record-1"))).isFalse();
            service.prepareValues(ability, incoming, incoming); // retained saved reference
        }
    }

    @Test
    void anOwnTicketCannotClaimAnotherUsersTemporaryOrSavedFile() {
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var client = org.mockito.Mockito.mock(FileTransferClient.class);
        beans.addBean("client", client);
        var service = new FileReferenceUploadService(new UploadDao(), org.mockito.Mockito.mock(FileReferenceUploadClaimDao.class), beans.getBeanProvider(ManagedFileAssetService.class),
                beans.getBeanProvider(FileTransferClient.class), java.time.Clock.systemUTC());
        var ability = new net.ximatai.muyun.spring.ability.AbstractAbilityService<ExternalDocument>("sales.document", ExternalDocument.class, new TestMemoryDao<>()) {};
        var incoming = new ExternalDocument(); incoming.setId("record-1"); incoming.setTenantId("tenant-a"); incoming.fileId = "file-1";
        try (var tenant = TenantContext.use("tenant-a");
             var actor = CurrentUserContext.use(CurrentUser.tenantUser("uploader", "Uploader", "tenant-a"))) {
            String ticket = service.registerTicket("tenant-a", uploadRequest("sales.document", null, "fileId", "record-1"), access());
            for (boolean temporary : new boolean[] {true, false}) {
                org.mockito.Mockito.when(client.readUploadFacts("file-1")).thenReturn(uploadFacts(temporary, "someone-else"));
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.bindUploadedFile("sales.document", ticket, "file-1"))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepareValues(ability, null, incoming))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
            try (var system = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
                service.prepareValues(ability, null, incoming);
            }
        }
    }

    private static FileReferenceUploadRequest uploadRequest(String module, String relation, String field, String record) {
        return new FileReferenceUploadRequest(module, relation, field, Map.of("id", record),
                new FileReferenceUploadFile("a.pdf", "application/pdf", 3), FileReferenceUploadIntent.CREATE);
    }

    private static FileTransferAccess access() {
        return new FileTransferAccess(FileTransferOperation.UPLOAD, null, "token", "url", java.time.Instant.now().plusSeconds(600));
    }

    private static FileTransferUploadFacts uploadFacts(boolean temporary, String user) {
        return new FileTransferUploadFacts(new FileTransferFileMetadata("file-1", "a.pdf", "pdf", "application/pdf", 3,
                "sha", "ACTIVE", temporary, java.time.Instant.now(), null, null), user, "tenant-a");
    }

    private static class ExternalDocument extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
        @net.ximatai.muyun.spring.common.model.file.FileReference
        private String fileId;
    }

    private static class Document extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
        @net.ximatai.muyun.spring.common.model.file.FileReference(storagePolicy = net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy.DATABASE_INLINE)
        private String fileId;
    }

    private FileReferenceReadRequest read(String module, String relation, String field, String record) {
        return new FileReferenceReadRequest(module, relation, field, Map.of("id", record), "file-1", FileTransferOperation.PREVIEW);
    }

    private static class UploadDao extends TestMemoryDao<FileReferenceUpload> implements FileReferenceUploadDao {}
}
