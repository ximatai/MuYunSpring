package net.ximatai.muyun.spring.platform.attachment;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.file.FileReferenceMetadata;
import net.ximatai.muyun.spring.common.model.file.FileReferenceMetadataField;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileReferenceSaveLifecycleListenerTest {
    @AfterEach
    void resetRuntime() {
        PlatformAbilityRuntime.resetEntitySaveLifecycleListener();
    }

    @Test
    void standardInsertValidatesAndNormalizesPreparedMetadataBeforeBinding() {
        AtomicInteger promotions = new AtomicInteger();
        var listener = listener(() -> spacedClient(promotions));
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(listener);
        var service = new RequiredDocumentService();
        var incoming = requiredDocument("file-new");

        service.insert(incoming);

        assertThat(service.select(incoming.getId()).getSourceFilename()).isEqualTo("source.pdf");
        assertThat(promotions).hasValue(1);
    }

    @Test
    void missingBusinessInputDoesNotPromoteThePreparedFile() {
        AtomicInteger promotions = new AtomicInteger();
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(listener(() -> spacedClient(promotions)));
        var service = new RequiredDocumentService();
        var incoming = requiredDocument("file-new");
        incoming.setLabel(null);

        assertThatThrownBy(() -> service.insert(incoming)).hasMessageContaining("label");

        assertThat(promotions).hasValue(0);
        assertThat(service.getDao().findById(incoming.getId())).isNull();
    }

    @Test
    void clearingAReferenceCannotPersistOrDeleteBeforeItsRequiredSnapshotIsValidated() {
        AtomicInteger promotions = new AtomicInteger();
        AtomicInteger deletions = new AtomicInteger();
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(listener(() -> client(promotions, deletions)));
        var service = new RequiredDocumentService();
        var existing = requiredDocument("file-old");
        service.insert(existing);
        var incoming = requiredDocument(null);
        incoming.setId(existing.getId());
        incoming.setSourceFilename(existing.getSourceFilename());

        assertThatThrownBy(() -> service.update(incoming)).hasMessageContaining("sourceFilename");

        assertThat(service.select(existing.getId()).getSourceFileId()).isEqualTo("file-old");
        assertThat(deletions).hasValue(0);
        assertThat(promotions).hasValue(1);
    }

    @Test
    void inlineMetadataIsPreparedBeforeStandardRequiredValidation() {
        var assets = mock(ManagedFileAssetService.class);
        var references = mock(ManagedFileAssetReferenceService.class);
        when(assets.readReferenceMetadata("tenant-a", "asset-1"))
                .thenReturn(new FileTransferFileMetadata("asset-1", "logo.png", "png", "image/png", 12,
                        "sha", "DATABASE_INLINE", false, Instant.now(), null, null));
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(listener(() -> null, () -> assets, () -> references));
        var service = new RequiredInlineDocumentService();
        var incoming = new RequiredInlineDocument();
        incoming.setTenantId("tenant-a");
        incoming.setAssetId("asset-1");

        inTransaction(() -> service.insert(incoming));

        assertThat(service.select(incoming.getId()).getAssetMimeType()).isEqualTo("image/png");
        verify(references).replaceFieldReferences("tenant-a", "test.required_inline_document", incoming.getId(),
                "assetId", List.of("asset-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicCrudPreparesRequiredSnapshotsAndPreservesOmittedReferences() {
        AtomicInteger promotions = new AtomicInteger();
        AtomicInteger deletions = new AtomicInteger();
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(listener(() -> client(promotions, deletions)));
        EntityDefinition definition = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.string("sourceFileId", "Source file").column("source_file_id"),
                FieldDefinition.string("note", "Note"),
                FieldDefinition.string("sourceFilename", "Source filename").column("source_filename")
                        .writeRules(new FieldWriteRules(true, true, TextNormalization.TRIM))))
                .withFileReferences(Map.of("sourceFileId", new FileReferenceDefinition(Set.of(), null, 1,
                        Map.of(FileReferenceMetadata.ORIGINAL_FILENAME, "sourceFilename"))));
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL"));
        when(operations.getDefaultSchemaName()).thenReturn("public");
        when(operations.insertItem(anyString(), anyString(), anyMap(), eq("id")))
                .thenAnswer(call -> call.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), eq("id"))).thenReturn(1);
        var service = new DynamicRecordRuntime(operations)
                .register(new ModuleDefinition("test.dynamic_document", "Documents", List.of(definition)))
                .entityService("test.dynamic_document", "document");
        var created = new DynamicRecord(definition).setValue("sourceFileId", "file-new");

        service.insert(created);

        assertThat(created.getValue("sourceFilename")).isEqualTo("source.pdf");
        assertThat(promotions).hasValue(1);
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "one", "version", 0, "deleted", false,
                "source_file_id", "file-old", "source_filename", "trusted.pdf")));
        var omitted = new DynamicRecord(definition).setValue("note", "changed");
        omitted.setId("one");
        assertThat(service.update(omitted)).isEqualTo(1);
        assertThat(omitted.getPlatformValues()).doesNotContainKeys("sourceFileId", "sourceFilename");
        assertThat(promotions).hasValue(1);
        assertThat(deletions).hasValue(0);

        var forged = new DynamicRecord(definition).setValue("sourceFilename", "forged.pdf");
        forged.setId("one");
        assertThat(service.update(forged)).isEqualTo(1);
        assertThat(forged.getValue("sourceFilename")).isEqualTo("trusted.pdf");

        var cleared = new DynamicRecord(definition).setValue("sourceFileId", null)
                .setValue("sourceFilename", "forged.pdf");
        cleared.setId("one");
        org.mockito.Mockito.clearInvocations(operations);
        assertThatThrownBy(() -> service.update(cleared)).hasMessageContaining("sourceFilename");
        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
        assertThat(deletions).hasValue(0);
    }

    @Test
    void standardCrudPreservesOwnershipConflictBeforeReadingAnAlreadyBoundFile() {
        var client = mock(FileTransferClient.class);
        var ownership = mock(FileReferenceOwnershipDao.class);
        when(ownership.existsById("file-bound")).thenReturn(true);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("client", client);
        var binding = new FileReferenceBindingService(beans.getBeanProvider(FileTransferClient.class), ownership);
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(new FileReferenceSaveLifecycleListener(
                () -> client, () -> null, () -> null, () -> binding));
        var service = new RequiredDocumentService();
        var incoming = requiredDocument("file-bound");

        assertThatThrownBy(() -> service.insert(incoming)).isInstanceOfSatisfying(PlatformException.class, error -> {
            assertThat(error.code()).isEqualTo("FILE_REFERENCE_ALREADY_BOUND");
            assertThat(error.httpStatus()).isEqualTo(409);
        });

        org.mockito.Mockito.verifyNoInteractions(client);
        verify(ownership, never()).insert(any());
        assertThat(service.getDao().findById(incoming.getId())).isNull();
    }

    private RequiredDocument requiredDocument(String fileId) {
        var value = new RequiredDocument();
        value.setSourceFileId(fileId);
        value.setLabel("Document");
        return value;
    }

    private FileTransferClient spacedClient(AtomicInteger promotions) {
        return new FileTransferClient() {
            @Override public FileTransferFileMetadata readMetadata(String fileId) {
                return new FileTransferFileMetadata(fileId, "  source.pdf  ", "pdf", "application/pdf", 1,
                        "sha", "temporary", true, Instant.now(), null, null);
            }
            @Override public FileTransferFileMetadata promote(String fileId) {
                promotions.incrementAndGet();
                return new FileTransferFileMetadata(fileId, "  source.pdf  ", "pdf", "application/pdf", 1,
                        "sha", "active", false, Instant.now(), null, null);
            }
            @Override public void delete(String fileId) { }
        };
    }

    private FileReferenceSaveLifecycleListener listener(java.util.function.Supplier<FileTransferClient> clients) {
        return listener(clients, () -> null, () -> null);
    }

    private FileReferenceSaveLifecycleListener listener(java.util.function.Supplier<FileTransferClient> clients,
            java.util.function.Supplier<ManagedFileAssetService> assets,
            java.util.function.Supplier<ManagedFileAssetReferenceService> references) {
        FileReferenceBindingService bindings = mock(FileReferenceBindingService.class);
        when(bindings.prepare(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            var client = clients.get();
            if (client == null) throw new PlatformException("file transfer client is not configured");
            return new FileReferenceConfirmationService(client).confirmTemporaryFile(call.getArgument(3), call.getArgument(2));
        });
        org.mockito.Mockito.when(bindings.bind(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                    var confirmation = new FileReferenceConfirmationService(clients.get());
                    return confirmation.promoteConfirmedFile(confirmation.confirmTemporaryFile(call.getArgument(5), call.getArgument(4)));
                });
        return new FileReferenceSaveLifecycleListener(clients, assets, references, () -> bindings);
    }

    @Test
    void promotesOnlyNewStaticFileReferences() {
        AtomicInteger promotions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = listener(() -> client(promotions));

        prepareAndSave(listener, new DocumentService(), document("file-old"), document("file-new"));
        listener.persisted(new DocumentService(), document("file-new"));

        assertThat(promotions).hasValue(1);
    }

    @Test
    void removesReplacedFileWithoutAClientDeletionIntent() {
        AtomicInteger promotions = new AtomicInteger();
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = listener(() -> client(promotions, deletions));
        Document existing = document("file-old");
        existing.setId("document-1");
        Document incoming = document("file-new");
        incoming.setId("document-1");

        prepareAndSave(listener, new DocumentService(), existing, incoming);
        listener.persisted(new DocumentService(), incoming);

        assertThat(promotions).hasValue(1);
        assertThat(deletions).hasValue(1);
    }

    @Test
    void removesOnlyTheDifferenceFromMultiFileReference() {
        AtomicInteger promotions = new AtomicInteger();
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = listener(() -> client(promotions, deletions));
        MultiDocument existing = multiDocument("file-old", "file-retained", "file-removed");
        existing.setId("document-1");
        MultiDocument incoming = multiDocument("file-retained", "file-new");
        incoming.setId("document-1");

        prepareAndSave(listener, new MultiDocumentService(), existing, incoming);
        listener.persisted(new MultiDocumentService(), incoming);

        assertThat(promotions).hasValue(1);
        assertThat(deletions).hasValue(2);
    }

    @Test
    void doesNotDeleteOldFilesWhenBusinessPersistenceFails() {
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = listener(
                () -> client(new AtomicInteger(), deletions));
        Document existing = document("file-old");
        existing.setId("document-1");
        Document incoming = document("file-new");
        incoming.setId("document-1");

        prepareAndSave(listener, new DocumentService(), existing, incoming);
        listener.persistFailed(new DocumentService(), incoming, new PlatformException("database failed"));
        listener.persisted(new DocumentService(), incoming);

        assertThat(deletions).hasValue(0);
    }

    @Test
    void handlesAChildEntityThroughItsOwnLifecycleWithoutRootPathMetadata() {
        AtomicInteger deletions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = listener(
                () -> client(new AtomicInteger(), deletions));
        Document existingLine = document("file-old");
        existingLine.setId("line-1");
        Document incomingLine = document(null);
        incomingLine.setId("line-1");

        prepareAndSave(listener, new DocumentService(), existingLine, incomingLine);
        listener.persisted(new DocumentService(), incomingLine);

        assertThat(deletions).hasValue(1);
    }

    @Test
    void promotesNewDynamicFileReferencesThroughTheSameLifecycle() {
        AtomicInteger promotions = new AtomicInteger();
        FileReferenceSaveLifecycleListener listener = listener(() -> client(promotions));
        EntityDefinition definition = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.string("sourceFileId", "Source file").column("source_file_id").length(64)))
                .withFileReferences(Map.of("sourceFileId", FileReferenceDefinition.unrestricted()));
        DynamicRecord existing = new DynamicRecord(definition).setValue("sourceFileId", "file-old");
        DynamicRecord incoming = new DynamicRecord(definition).setValue("sourceFileId", "file-new");

        prepareAndSave(listener, new DynamicDocumentService(), existing, incoming);

        assertThat(promotions).hasValue(1);
    }

    @Test
    void hydratesDeclaredMetadataBeforePromotionAndRejectsClientValues() {
        FileReferenceSaveLifecycleListener listener = listener(() -> client(new AtomicInteger()));
        Document incoming = document("file-new");
        incoming.setSourceFilename("forged.pdf");
        incoming.setSourceFileSize(999L);

        prepareAndSave(listener, new DocumentService(), null, incoming);

        assertThat(incoming.getSourceFilename()).isEqualTo("source.pdf");
        assertThat(incoming.getSourceFileSize()).isEqualTo(1L);
    }

    @Test
    void preservesOrClearsManagedMetadataWithTheFileReference() {
        FileReferenceSaveLifecycleListener listener = listener(() -> client(new AtomicInteger()));
        Document existing = document("file-old");
        existing.setSourceFilename("trusted.pdf");
        existing.setSourceFileSize(5L);
        Document retained = document("file-old");
        retained.setSourceFilename("forged.pdf");
        retained.setSourceFileSize(999L);

        prepareAndSave(listener, new DocumentService(), existing, retained);

        assertThat(retained.getSourceFilename()).isEqualTo("trusted.pdf");
        assertThat(retained.getSourceFileSize()).isEqualTo(5L);

        Document cleared = document(null);
        cleared.setSourceFilename("forged.pdf");
        cleared.setSourceFileSize(999L);
        prepareAndSave(listener, new DocumentService(), existing, cleared);

        assertThat(cleared.getSourceFilename()).isNull();
        assertThat(cleared.getSourceFileSize()).isNull();
    }

    @Test
    void hydratesDynamicMetadataThroughTheSameSourceNeutralDefinition() {
        FileReferenceSaveLifecycleListener listener = listener(() -> client(new AtomicInteger()));
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

        prepareAndSave(listener, new DynamicDocumentService(), null, incoming);

        assertThat(incoming.getValue("sourceFilename")).isEqualTo("source.pdf");
        assertThat(incoming.getValue("sourceFileSize")).isEqualTo(1L);
    }

    @Test
    void hydratesInlineAssetMetadataThroughTheSameReferenceMetadataContract() {
        ManagedFileAssetService assets = mock(ManagedFileAssetService.class);
        ManagedFileAssetReferenceService references = mock(ManagedFileAssetReferenceService.class);
        when(assets.readReferenceMetadata("tenant-a", "asset-1"))
                .thenReturn(new FileTransferFileMetadata("asset-1", "logo.png", "png", "image/png", 12,
                        "sha", "DATABASE_INLINE", false, Instant.now(), null, null));
        FileReferenceSaveLifecycleListener listener = listener(() -> null, () -> assets, () -> references);
        InlineDocument incoming = new InlineDocument();
        incoming.setId("document-1");
        incoming.setTenantId("tenant-a");
        incoming.setAssetId("asset-1");
        incoming.setAssetMimeType("forged/type");

        inTransaction(() -> {
            prepareAndSave(listener, new InlineDocumentService(), null, incoming);
            listener.persisted(new InlineDocumentService(), incoming);
        });

        assertThat(incoming.getAssetMimeType()).isEqualTo("image/png");
        verify(references).replaceFieldReferences("tenant-a", "test.inline_document", "document-1", "assetId",
                List.of("asset-1"));
    }

    @Test
    void rejectsInlineReferenceSaveOutsideAnActiveTransaction() {
        ManagedFileAssetService assets = mock(ManagedFileAssetService.class);
        ManagedFileAssetReferenceService references = mock(ManagedFileAssetReferenceService.class);
        FileReferenceSaveLifecycleListener listener = listener(() -> null, () -> assets, () -> references);
        InlineDocument incoming = new InlineDocument();
        incoming.setId("document-1");
        incoming.setTenantId("tenant-a");
        incoming.setAssetId("asset-1");

        assertThatThrownBy(() -> prepareAndSave(listener, new InlineDocumentService(), null, incoming))
                .isInstanceOf(PlatformException.class)
                .hasMessage("database inline file reference save requires an active transaction");
    }

    @Test
    void rejectsInlineAssetCreationOutsideAnActiveTransaction() {
        ManagedFileAssetService assets = new ManagedFileAssetService(mock(ManagedFileAssetDao.class));

        assertThatThrownBy(() -> assets.createInline("tenant-a", "data:image/png;base64,AA=="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("managed inline asset creation requires an active transaction");
    }

    @Test
    void rejectsNewReferenceWhenTransferIsUnavailable() {
        FileReferenceSaveLifecycleListener listener = listener(() -> null);

        assertThatThrownBy(() -> prepareAndSave(listener, new DocumentService(), null, document("file-new")))
                .isInstanceOf(PlatformException.class)
                .hasMessage("file transfer client is not configured");
    }

    private <T extends net.ximatai.muyun.spring.common.model.contract.EntityContract> void prepareAndSave(
            FileReferenceSaveLifecycleListener listener, net.ximatai.muyun.spring.ability.CrudAbility<T> service,
            T existing, T incoming) {
        listener.prepareValues(service, existing, incoming);
        listener.beforeSave(service, existing, incoming);
    }

    private FileTransferClient client(AtomicInteger promotions) { return client(promotions, new AtomicInteger()); }

    private FileTransferClient client(AtomicInteger promotions, AtomicInteger deletions) {
        return new FileTransferClient() {
            @Override public FileTransferFileMetadata readMetadata(String fileId) {
                return new FileTransferFileMetadata(fileId, "source.pdf", "pdf", "application/pdf", 1, "sha",
                        "temporary", true, Instant.now(), null, null);
            }
            @Override public FileTransferFileMetadata promote(String fileId) {
                promotions.incrementAndGet();
                return new FileTransferFileMetadata(fileId, "source.pdf", "pdf", "application/pdf", 1, "sha",
                        "active", false, Instant.now(), null, null);
            }
            @Override public void delete(String fileId) { deletions.incrementAndGet(); }
        };
    }

    private void inTransaction(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            action.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Document document(String fileId) { Document value = new Document(); value.setSourceFileId(fileId); return value; }
    private MultiDocument multiDocument(String... fileIds) {
        MultiDocument value = new MultiDocument();
        value.setSourceFileIds(new LinkedHashSet<>(List.of(fileIds)));
        return value;
    }

    @Getter @Setter
    static class Document extends StandardEntity {
        @FileReference
        private String sourceFileId;
        @FileReferenceMetadataField(source = "sourceFileId", value = FileReferenceMetadata.ORIGINAL_FILENAME)
        private String sourceFilename;
        @FileReferenceMetadataField(source = "sourceFileId", value = FileReferenceMetadata.SIZE_BYTES)
        private Long sourceFileSize;
    }
    @Getter @Setter
    static class MultiDocument extends StandardEntity {
        @Column(name = "source_file_ids", type = ColumnType.JSON_SET)
        @FileReference(maxFiles = 3)
        private LinkedHashSet<String> sourceFileIds;
    }
    @Getter @Setter
    static class InlineDocument extends StandardEntity {
        @FileReference(allowedMediaTypes = "image/png", storagePolicy = FileReferenceStoragePolicy.DATABASE_INLINE)
        private String assetId;
        @FileReferenceMetadataField(source = "assetId", value = FileReferenceMetadata.MIME_TYPE)
        private String assetMimeType;
    }
    @Getter @Setter
    @Required(fields = "sourceFilename")
    @NormalizeText(fields = "sourceFilename")
    static class RequiredDocument extends Document {
        @Required
        private String label;
    }
    @Required(fields = "assetMimeType")
    static class RequiredInlineDocument extends InlineDocument { }
    static class RequiredDocumentService extends AbstractAbilityService<RequiredDocument> {
        RequiredDocumentService() { super("test.required_document", RequiredDocument.class, new TestMemoryDao<>()); }
    }
    static class RequiredInlineDocumentService extends AbstractAbilityService<RequiredInlineDocument> {
        RequiredInlineDocumentService() { super("test.required_inline_document", RequiredInlineDocument.class, new TestMemoryDao<>()); }
    }
    static class DocumentService extends AbstractAbilityService<Document> {
        DocumentService() { super("test.document", Document.class, new TestMemoryDao<>()); }
    }
    static class MultiDocumentService extends AbstractAbilityService<MultiDocument> {
        MultiDocumentService() { super("test.multi_document", MultiDocument.class, new TestMemoryDao<>()); }
    }
    static class InlineDocumentService extends AbstractAbilityService<InlineDocument> {
        InlineDocumentService() { super("test.inline_document", InlineDocument.class, new TestMemoryDao<>()); }
    }
    static class DynamicDocumentService extends AbstractAbilityService<DynamicRecord> {
        DynamicDocumentService() { super("test.dynamic_document", DynamicRecord.class, new TestMemoryDao<>()); }
    }
}
