package net.ximatai.muyun.spring.platform.attachment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.file.FileReferenceMetadata;
import net.ximatai.muyun.spring.common.model.file.FileReferenceMetadataField;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileReferenceSaveLifecycleListenerTest {
    @Test
    void promotesOnlyNewStaticFileReferences() {
        AtomicInteger promotions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> client(promotions));

        listener.beforeSave(new DocumentService(), document("file-old"), document("file-new"));
        listener.persisted(new DocumentService(), document("file-new"));

        assertThat(promotions).hasValue(1);
    }

    @Test
    void removesReplacedFileWithoutAClientDeletionIntent() {
        AtomicInteger promotions = new AtomicInteger();
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> client(promotions, deletions));
        Document existing = document("file-old");
        existing.setId("document-1");
        Document incoming = document("file-new");
        incoming.setId("document-1");

        listener.beforeSave(new DocumentService(), existing, incoming);
        listener.persisted(new DocumentService(), incoming);

        assertThat(promotions).hasValue(1);
        assertThat(deletions).hasValue(1);
    }

    @Test
    void removesOnlyTheDifferenceFromMultiFileReference() {
        AtomicInteger promotions = new AtomicInteger();
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> client(promotions, deletions));
        MultiDocument existing = multiDocument("file-old", "file-retained", "file-removed");
        existing.setId("document-1");
        MultiDocument incoming = multiDocument("file-retained", "file-new");
        incoming.setId("document-1");

        listener.beforeSave(new MultiDocumentService(), existing, incoming);
        listener.persisted(new MultiDocumentService(), incoming);

        assertThat(promotions).hasValue(1);
        assertThat(deletions).hasValue(2);
    }

    @Test
    void doesNotDeleteOldFilesWhenBusinessPersistenceFails() {
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(
                () -> client(new AtomicInteger(), deletions));
        Document existing = document("file-old");
        existing.setId("document-1");
        Document incoming = document("file-new");
        incoming.setId("document-1");

        listener.beforeSave(new DocumentService(), existing, incoming);
        listener.persistFailed(new DocumentService(), incoming, new PlatformException("database failed"));
        listener.persisted(new DocumentService(), incoming);

        assertThat(deletions).hasValue(0);
    }

    @Test
    void handlesAChildEntityThroughItsOwnLifecycleWithoutRootPathMetadata() {
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(
                () -> client(new AtomicInteger(), deletions));
        Document existingLine = document("file-old");
        existingLine.setId("line-1");
        Document incomingLine = document(null);
        incomingLine.setId("line-1");

        listener.beforeSave(new DocumentService(), existingLine, incomingLine);
        listener.persisted(new DocumentService(), incomingLine);

        assertThat(deletions).hasValue(1);
    }

    @Test
    void promotesNewDynamicFileReferencesThroughTheSameLifecycle() {
        AtomicInteger promotions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> client(promotions));
        EntityDefinition definition = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.string("sourceFileId", "Source file").column("source_file_id").length(64)))
                .withFileReferences(Map.of("sourceFileId", FileReferenceDefinition.unrestricted()));
        DynamicRecord existing = new DynamicRecord(definition).setValue("sourceFileId", "file-old");
        DynamicRecord incoming = new DynamicRecord(definition).setValue("sourceFileId", "file-new");

        listener.beforeSave(new DynamicDocumentService(), existing, incoming);

        assertThat(promotions).hasValue(1);
    }

    @Test
    void hydratesDeclaredMetadataFromThePromotedFileAndRejectsClientValues() {
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> client(new AtomicInteger()));
        Document incoming = document("file-new");
        incoming.setSourceFilename("forged.pdf");
        incoming.setSourceFileSize(999L);

        listener.beforeSave(new DocumentService(), null, incoming);

        assertThat(incoming.getSourceFilename()).isEqualTo("source.pdf");
        assertThat(incoming.getSourceFileSize()).isEqualTo(1L);
    }

    @Test
    void preservesOrClearsManagedMetadataWithTheFileReference() {
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> client(new AtomicInteger()));
        Document existing = document("file-old");
        existing.setSourceFilename("trusted.pdf");
        existing.setSourceFileSize(5L);
        Document retained = document("file-old");
        retained.setSourceFilename("forged.pdf");
        retained.setSourceFileSize(999L);

        listener.beforeSave(new DocumentService(), existing, retained);

        assertThat(retained.getSourceFilename()).isEqualTo("trusted.pdf");
        assertThat(retained.getSourceFileSize()).isEqualTo(5L);

        Document cleared = document(null);
        cleared.setSourceFilename("forged.pdf");
        cleared.setSourceFileSize(999L);
        listener.beforeSave(new DocumentService(), existing, cleared);

        assertThat(cleared.getSourceFilename()).isNull();
        assertThat(cleared.getSourceFileSize()).isNull();
    }

    @Test
    void hydratesDynamicMetadataThroughTheSameSourceNeutralDefinition() {
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> client(new AtomicInteger()));
        EntityDefinition definition = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.string("sourceFileId", "Source file").column("source_file_id").length(64),
                FieldDefinition.string("sourceFilename", "Source filename").column("source_filename").length(255),
                FieldDefinition.longInteger("sourceFileSize", "Source file size").column("source_file_size")))
                .withFileReferences(Map.of("sourceFileId", new FileReferenceDefinition(Set.of(), null, 1,
                        Map.of(FileReferenceMetadata.ORIGINAL_FILENAME, "sourceFilename",
                                FileReferenceMetadata.SIZE_BYTES, "sourceFileSize"))));
        DynamicRecord incoming = new DynamicRecord(definition)
                .setValue("sourceFileId", "file-new")
                .setValue("sourceFilename", "forged.pdf")
                .setValue("sourceFileSize", 999L);

        listener.beforeSave(new DynamicDocumentService(), null, incoming);

        assertThat(incoming.getValue("sourceFilename")).isEqualTo("source.pdf");
        assertThat(incoming.getValue("sourceFileSize")).isEqualTo(1L);
    }

    @Test
    void rejectsNewReferenceWhenTransferIsUnavailable() {
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> null);

        assertThatThrownBy(() -> listener.beforeSave(new DocumentService(), null, document("file-new")))
                .isInstanceOf(PlatformException.class)
                .hasMessage("file transfer client is not configured");
    }

    @Test
    void recordsAlreadyPromotedFilesWhenLaterMultiFilePromotionFails() {
        Logger logger = (Logger) LoggerFactory.getLogger(FileReferenceSaveLifecycleListener.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(
                    () -> partiallyFailingClient("file-fails"));

            assertThatThrownBy(() -> listener.beforeSave(new MultiDocumentService(), null,
                    multiDocument("file-promoted", "file-fails")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessage("promotion failed");

            assertThat(events.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("File reference was promoted but record save did not complete")
                        .contains("file-promoted");
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    private FileTransferClient client(AtomicInteger promotions) { return client(promotions, new AtomicInteger()); }

    private FileTransferClient client(AtomicInteger promotions, AtomicInteger deletions) {
        return new FileTransferClient() {
            @Override public FileTransferFileMetadata readMetadata(String fileId) {
                return new FileTransferFileMetadata(fileId, "source.pdf", "pdf", "application/pdf", 1, "sha",
                        "temporary", true, Instant.now());
            }
            @Override public FileTransferFileMetadata promote(String fileId) {
                promotions.incrementAndGet();
                return new FileTransferFileMetadata(fileId, "source.pdf", "pdf", "application/pdf", 1, "sha",
                        "active", false, Instant.now());
            }
            @Override public void delete(String fileId) { deletions.incrementAndGet(); }
        };
    }

    private FileTransferClient partiallyFailingClient(String failingFileId) {
        return new FileTransferClient() {
            @Override public FileTransferFileMetadata readMetadata(String fileId) {
                return new FileTransferFileMetadata(fileId, "source.pdf", "pdf", "application/pdf", 1, "sha",
                        "temporary", true, Instant.now());
            }

            @Override public FileTransferFileMetadata promote(String fileId) {
                if (failingFileId.equals(fileId)) {
                    throw new PlatformException("promotion failed");
                }
                return new FileTransferFileMetadata(fileId, "source.pdf", "pdf", "application/pdf", 1, "sha",
                        "active", false, Instant.now());
            }
        };
    }

    private Document document(String fileId) { Document value = new Document(); value.setSourceFileId(fileId); return value; }
    private MultiDocument multiDocument(String... fileIds) {
        MultiDocument value = new MultiDocument();
        value.setSourceFileIds(new LinkedHashSet<>(List.of(fileIds)));
        return value;
    }

    @Getter @Setter
    static class Document extends StandardEntity {
        @FileReference(metadataFields = {
                @FileReferenceMetadataField(value = FileReferenceMetadata.ORIGINAL_FILENAME, field = "sourceFilename"),
                @FileReferenceMetadataField(value = FileReferenceMetadata.SIZE_BYTES, field = "sourceFileSize")
        })
        private String sourceFileId;
        private String sourceFilename;
        private Long sourceFileSize;
    }
    @Getter @Setter
    static class MultiDocument extends StandardEntity {
        @Column(name = "source_file_ids", type = ColumnType.JSON_SET)
        @FileReference(maxFiles = 3)
        private LinkedHashSet<String> sourceFileIds;
    }
    static class DocumentService extends AbstractAbilityService<Document> {
        DocumentService() { super("test.document", Document.class, new TestMemoryDao<>()); }
    }
    static class MultiDocumentService extends AbstractAbilityService<MultiDocument> {
        MultiDocumentService() { super("test.multi_document", MultiDocument.class, new TestMemoryDao<>()); }
    }
    static class DynamicDocumentService extends AbstractAbilityService<DynamicRecord> {
        DynamicDocumentService() { super("test.dynamic_document", DynamicRecord.class, new TestMemoryDao<>()); }
    }
}
