package net.ximatai.muyun.spring.platform.attachment;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileReferenceSaveLifecycleListenerTest {
    @Test
    void promotesOnlyNewStaticFileReferences() {
        AtomicInteger promotions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> client(promotions));
        Document existing = document("file-old");
        Document incoming = document("file-new");

        listener.beforeSave(new DocumentService(), existing, incoming);
        listener.persisted(new DocumentService(), incoming);
        listener.beforeSave(new DocumentService(), incoming, document("file-new"));

        assertThat(promotions).hasValue(1);
    }

    @Test
    void rejectsAConfiguredReferenceWhenFileTransferIsUnavailable() {
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(() -> null);

        assertThatThrownBy(() -> listener.beforeSave(new DocumentService(), null, document("file-new")))
                .isInstanceOf(PlatformException.class)
                .hasMessage("file transfer client is not configured");
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

    private FileTransferClient client(AtomicInteger promotions) {
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
        };
    }

    private Document document(String fileId) { Document value = new Document(); value.setSourceFileId(fileId); return value; }

    @Getter @Setter
    static class Document extends StandardEntity { @FileReference private String sourceFileId; }
    static class DocumentService extends AbstractAbilityService<Document> {
        DocumentService() { super("test.document", Document.class, new TestMemoryDao<>()); }
    }
    static class DynamicDocumentService extends AbstractAbilityService<DynamicRecord> {
        DynamicDocumentService() { super("test.dynamic_document", DynamicRecord.class, new TestMemoryDao<>()); }
    }
}
