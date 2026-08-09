package net.ximatai.muyun.spring.platform.attachment;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.mutation.RecordFileDeletionIntent;
import net.ximatai.muyun.spring.common.mutation.RecordMutationPath;
import net.ximatai.muyun.spring.common.mutation.RecordMutationPathNode;
import net.ximatai.muyun.spring.common.mutation.RecordSaveMutationMetadata;
import net.ximatai.muyun.spring.common.mutation.RecordSaveMutationMetadataContext;
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

    @Test
    void deletesAnExplicitlyRemovedOldFileOnlyAfterPersistenceSucceeds() {
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(
                () -> client(new AtomicInteger(), deletions));
        Document existing = document("file-old");
        existing.setId("document-1");
        Document incoming = document("file-new");
        incoming.setId("document-1");
        RecordSaveMutationMetadata metadata = new RecordSaveMutationMetadata(List.of(
                new RecordFileDeletionIntent(RecordMutationPath.root("document-1"), "sourceFileId", "file-old")
        ));

        try (RecordSaveMutationMetadataContext.Scope ignored = RecordSaveMutationMetadataContext.open(metadata)) {
            listener.beforeSave(new DocumentService(), existing, incoming);
            assertThat(deletions).hasValue(0);
            listener.persisted(new DocumentService(), incoming);
        }

        assertThat(deletions).hasValue(1);
    }

    @Test
    void rejectsDeletionThatDoesNotMatchTheExistingFileReference() {
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(
                () -> client(new AtomicInteger()));
        Document existing = document("file-old");
        existing.setId("document-1");
        Document incoming = document("file-new");
        incoming.setId("document-1");
        RecordSaveMutationMetadata metadata = new RecordSaveMutationMetadata(List.of(
                new RecordFileDeletionIntent(RecordMutationPath.root("document-1"), "sourceFileId", "file-other")
        ));

        try (RecordSaveMutationMetadataContext.Scope ignored = RecordSaveMutationMetadataContext.open(metadata)) {
            assertThatThrownBy(() -> listener.beforeSave(new DocumentService(), existing, incoming))
                    .isInstanceOf(PlatformException.class)
                    .hasMessage("file deletion does not match existing reference: sourceFileId");
        }
    }

    @Test
    void doesNotDeleteWhenBusinessPersistenceFails() {
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(
                () -> client(new AtomicInteger(), deletions));
        Document existing = document("file-old");
        existing.setId("document-1");
        Document incoming = document("file-new");
        incoming.setId("document-1");
        RecordSaveMutationMetadata metadata = new RecordSaveMutationMetadata(List.of(
                new RecordFileDeletionIntent(RecordMutationPath.root("document-1"), "sourceFileId", "file-old")
        ));

        try (RecordSaveMutationMetadataContext.Scope ignored = RecordSaveMutationMetadataContext.open(metadata)) {
            listener.beforeSave(new DocumentService(), existing, incoming);
            listener.persistFailed(new DocumentService(), incoming, new PlatformException("database failed"));
            listener.persisted(new DocumentService(), incoming);
        }

        assertThat(deletions).hasValue(0);
    }

    @Test
    void rejectsChildPathsWhenTheParentDoesNotDeclareThatRelation() {
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(
                () -> client(new AtomicInteger()));
        Document existing = document("file-old");
        existing.setId("document-1");
        Document incoming = document("file-new");
        incoming.setId("document-1");
        RecordSaveMutationMetadata metadata = new RecordSaveMutationMetadata(List.of(
                new RecordFileDeletionIntent(new RecordMutationPath(List.of(
                        new RecordMutationPathNode(null, "document-1"),
                        new RecordMutationPathNode("lines", "line-1")
                )), "sourceFileId", "file-old")
        ));

        try (RecordSaveMutationMetadataContext.Scope ignored = RecordSaveMutationMetadataContext.open(metadata)) {
            assertThatThrownBy(() -> listener.beforeSave(new DocumentService(), existing, incoming))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("file deletion path does not support child relations");
        }
    }

    @Test
    void deletesAnExplicitlyReplacedFileFromOneStaticChildRelation() {
        AtomicInteger deletions = new AtomicInteger();
        Document existingLine = document("file-old");
        existingLine.setId("line-1");
        Document incomingLine = document("file-new");
        incomingLine.setId("line-1");
        Folder existing = folder("folder-1", List.of(existingLine));
        Folder incoming = folder("folder-1", List.of(incomingLine));
        FolderService service = new FolderService(new DocumentChildService(List.of(existingLine)));
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(
                () -> client(new AtomicInteger(), deletions));
        RecordSaveMutationMetadata metadata = new RecordSaveMutationMetadata(List.of(
                new RecordFileDeletionIntent(new RecordMutationPath(List.of(
                        new RecordMutationPathNode(null, "folder-1"),
                        new RecordMutationPathNode("lines", "line-1")
                )), "sourceFileId", "file-old")
        ));

        try (RecordSaveMutationMetadataContext.Scope ignored = RecordSaveMutationMetadataContext.open(metadata)) {
            listener.beforeSave(service, existing, incoming);
            listener.persisted(service, incoming);
        }

        assertThat(deletions).hasValue(1);
    }

    @Test
    void promotesOnlyNewFilesAndDeletesOnlyExplicitlyRemovedFilesFromOneMultiFileField() {
        AtomicInteger promotions = new AtomicInteger();
        AtomicInteger deletions = new AtomicInteger();
        MultiDocument existing = multiDocument("file-old", "file-retained");
        existing.setId("document-1");
        MultiDocument incoming = multiDocument("file-retained", "file-new");
        incoming.setId("document-1");
        FileReferenceSaveLifecycleListener listener = new FileReferenceSaveLifecycleListener(
                () -> client(promotions, deletions));
        RecordSaveMutationMetadata metadata = new RecordSaveMutationMetadata(List.of(
                new RecordFileDeletionIntent(RecordMutationPath.root("document-1"), "sourceFileIds", "file-old")
        ));

        try (RecordSaveMutationMetadataContext.Scope ignored = RecordSaveMutationMetadataContext.open(metadata)) {
            listener.beforeSave(new MultiDocumentService(), existing, incoming);
            listener.persisted(new MultiDocumentService(), incoming);
        }

        assertThat(promotions).hasValue(1);
        assertThat(deletions).hasValue(1);
    }

    private FileTransferClient client(AtomicInteger promotions) {
        return client(promotions, new AtomicInteger());
    }

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

    private Document document(String fileId) { Document value = new Document(); value.setSourceFileId(fileId); return value; }
    private MultiDocument multiDocument(String... fileIds) {
        MultiDocument value = new MultiDocument();
        value.setSourceFileIds(new java.util.LinkedHashSet<>(List.of(fileIds)));
        return value;
    }
    private Folder folder(String id, List<Document> lines) {
        Folder value = new Folder();
        value.setId(id);
        value.setLines(lines);
        return value;
    }

    @Getter @Setter
    static class Document extends StandardEntity { @FileReference private String sourceFileId; }
    @Getter @Setter
    static class MultiDocument extends StandardEntity {
        @Column(name = "source_file_ids", type = ColumnType.JSON_SET)
        @FileReference(maxFiles = 3)
        private java.util.Set<String> sourceFileIds;
    }
    static class DocumentService extends AbstractAbilityService<Document> {
        DocumentService() { super("test.document", Document.class, new TestMemoryDao<>()); }
    }
    static class MultiDocumentService extends AbstractAbilityService<MultiDocument> {
        MultiDocumentService() { super("test.multi_document", MultiDocument.class, new TestMemoryDao<>()); }
    }
    static class DocumentChildService extends DocumentService implements ChildAbility<Document> {
        private final List<Document> rows;
        DocumentChildService(List<Document> rows) { this.rows = rows; }
        @Override public List<Document> selectChildRows(Criteria criteria) { return rows; }
    }
    @Getter @Setter
    static class Folder extends StandardEntity { private List<Document> lines; }
    static class FolderService extends AbstractAbilityService<Folder> implements ChildrenAbility<Folder> {
        private final DocumentChildService childService;
        FolderService(DocumentChildService childService) {
            super("test.folder", Folder.class, new TestMemoryDao<>());
            this.childService = childService;
        }
        @Override public List<ChildRelation<? extends net.ximatai.muyun.spring.common.model.contract.EntityContract, Folder>> childRelations() {
            return List.of(new ChildRelation<>("lines", childService, (child, parentId) -> { }, "folderId", Folder::getLines));
        }
    }
    static class DynamicDocumentService extends AbstractAbilityService<DynamicRecord> {
        DynamicDocumentService() { super("test.dynamic_document", DynamicRecord.class, new TestMemoryDao<>()); }
    }
}
