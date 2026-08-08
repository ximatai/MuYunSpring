package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileReferenceConfirmationServiceTest {
    @Test
    void shouldConfirmAConstrainedTemporaryFile() {
        RecordingClient client = new RecordingClient(metadata(true, "application/pdf", 1024));
        FileReferenceConfirmationService service = new FileReferenceConfirmationService(client);

        FileTransferFileMetadata metadata = service.confirmTemporaryFile(
                new FileReferenceDefinition(Set.of("application/pdf"), 2048L), "file-1");

        assertThat(metadata.fileId()).isEqualTo("file-1");
    }

    @Test
    void shouldRejectPermanentOrConstraintViolatingFiles() {
        FileReferenceConfirmationService permanent = new FileReferenceConfirmationService(
                new RecordingClient(metadata(false, "application/pdf", 1)));
        assertThatThrownBy(() -> permanent.confirmTemporaryFile(FileReferenceDefinition.unrestricted(), "file-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessage("file reference must bind a temporary file: file-1");

        FileReferenceConfirmationService tooLarge = new FileReferenceConfirmationService(
                new RecordingClient(metadata(true, "image/jpeg", 10)));
        assertThatThrownBy(() -> tooLarge.confirmTemporaryFile(
                new FileReferenceDefinition(Set.of("application/pdf"), 5L), "file-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessage("file reference media type is not allowed: image/jpeg");
    }

    @Test
    void shouldPromoteConfirmedTemporaryFileBeforeBusinessPersistence() {
        RecordingClient client = new RecordingClient(metadata(true, "application/pdf", 1));
        FileReferenceConfirmationService service = new FileReferenceConfirmationService(client);

        FileTransferFileMetadata metadata = service.confirmAndPromote(
                FileReferenceDefinition.unrestricted(), "file-1");

        assertThat(metadata.fileId()).isEqualTo("file-1");
        assertThat(client.promotedFileId).isEqualTo("file-1");
    }

    @Test
    void shouldAbortTheSavePathWhenPromotionFails() {
        RecordingClient client = new RecordingClient(metadata(true, "application/pdf", 1));
        client.promoteFailure = new PlatformException("fileserver unavailable");
        FileReferenceConfirmationService service = new FileReferenceConfirmationService(client);

        assertThatThrownBy(() -> service.confirmAndPromote(FileReferenceDefinition.unrestricted(), "file-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessage("fileserver unavailable");
    }

    @Test
    void shouldRejectAnUnconfirmedPromotionResult() {
        RecordingClient client = new RecordingClient(metadata(true, "application/pdf", 1));
        client.promoteResult = metadata(true, "application/pdf", 1);
        FileReferenceConfirmationService service = new FileReferenceConfirmationService(client);

        assertThatThrownBy(() -> service.confirmAndPromote(FileReferenceDefinition.unrestricted(), "file-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessage("file reference was not promoted: file-1");
    }

    private static FileTransferFileMetadata metadata(boolean temporary, String mimeType, long sizeBytes) {
        return new FileTransferFileMetadata("file-1", "source.pdf", "pdf", mimeType, sizeBytes, "sha", "ACTIVE",
                temporary, Instant.parse("2026-08-09T00:00:00Z"));
    }

    private static final class RecordingClient implements FileTransferClient {
        private final FileTransferFileMetadata metadata;
        private String promotedFileId;
        private RuntimeException promoteFailure;
        private FileTransferFileMetadata promoteResult;

        private RecordingClient(FileTransferFileMetadata metadata) {
            this.metadata = metadata;
        }

        @Override public FileTransferFileMetadata readMetadata(String fileId) { return metadata; }

        @Override public FileTransferFileMetadata promote(String fileId) {
            promotedFileId = fileId;
            if (promoteFailure != null) throw promoteFailure;
            return promoteResult == null ? new FileTransferFileMetadata(metadata.fileId(), metadata.originalFilename(),
                    metadata.extension(), metadata.mimeType(), metadata.sizeBytes(), metadata.sha256(), "ACTIVE", false,
                    metadata.uploadedAt()) : promoteResult;
        }
    }
}
