package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.EntitySaveLifecycleListener;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import net.ximatai.muyun.spring.common.model.file.FileReferenceMetadata;
import net.ximatai.muyun.spring.common.model.file.FileReferenceMetadataField;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Applies the same file-reference lifecycle to static models and dynamic records. */
public final class FileReferenceSaveLifecycleListener implements EntitySaveLifecycleListener {
    private static final Logger log = LoggerFactory.getLogger(FileReferenceSaveLifecycleListener.class);
    private final Supplier<FileTransferClient> clientSupplier;
    private final Supplier<ManagedFileAssetService> managedAssetServiceSupplier;
    private final Supplier<ManagedFileAssetReferenceService> managedAssetReferenceServiceSupplier;
    private final ThreadLocal<Map<EntityContract, java.util.List<ResolvedFileDeletion>>> pendingDeletions =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private final ThreadLocal<Map<EntityContract, InlineReferenceChange>> pendingInlineReferences =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private final Supplier<FileReferenceBindingService> bindingServiceSupplier;

    public FileReferenceSaveLifecycleListener(Supplier<FileTransferClient> clientSupplier,
                                              Supplier<ManagedFileAssetService> managedAssetServiceSupplier,
                                              Supplier<ManagedFileAssetReferenceService> managedAssetReferenceServiceSupplier,
                                              Supplier<FileReferenceBindingService> bindingServiceSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier);
        this.managedAssetServiceSupplier = Objects.requireNonNull(managedAssetServiceSupplier);
        this.managedAssetReferenceServiceSupplier = Objects.requireNonNull(managedAssetReferenceServiceSupplier);
        this.bindingServiceSupplier = Objects.requireNonNull(bindingServiceSupplier);
    }

    @Override
    public <T extends EntityContract> void prepareValues(CrudAbility<T> ability, T existing, T incoming) {
        existing = existingRecord(ability, existing, incoming);
        Map<String, FileReferenceDefinition> definitions = definitions(incoming);
        if (incoming instanceof DynamicRecord record) {
            var submitted = record.getPlatformValues().keySet();
            T snapshot = existing;
            definitions.forEach((source, definition) -> {
                if (!submitted.contains(source)) {
                    definition.metadataFields().values().stream().filter(submitted::contains)
                            .forEach(field -> writeValue(incoming, field, rawValue(snapshot, field)));
                }
            });
        }
        definitions = writtenDefinitions(incoming);
        for (Map.Entry<String, FileReferenceDefinition> entry : fileServerDefinitions(definitions).entrySet()) {
            java.util.List<String> incomingIds = values(incoming, entry.getKey(), entry.getValue());
            java.util.List<String> existingIds = values(existing, entry.getKey(), entry.getValue());
            Map<String, FileTransferFileMetadata> metadata = new LinkedHashMap<>();
            for (String fileId : incomingIds) {
                if (!existingIds.contains(fileId)) {
                    metadata.put(fileId, requireBindingService().prepare(
                            ability.getModuleAlias(), entry.getKey(), fileId, entry.getValue()));
                }
            }
            applyMetadataFields(incoming, existing, entry.getKey(), entry.getValue(), incomingIds, metadata);
        }
        Map<String, FileReferenceDefinition> inline = inlineDefinitions(definitions);
        requireTransactionForInlineReferences(hasInlineReferenceValues(existing, incoming, inline));
        applyInlineMetadataFields(incoming, existing, inline);
    }

    private <T extends EntityContract> T existingRecord(CrudAbility<T> ability, T existing, T incoming) {
        return existing == null && incoming.getId() != null && !incoming.getId().isBlank()
                ? ability.selectActiveRaw(incoming.getId()) : existing;
    }

    /** An omitted dynamic field is not a request to clear its reference or its metadata. */
    private Map<String, FileReferenceDefinition> writtenDefinitions(EntityContract incoming) {
        Map<String, FileReferenceDefinition> definitions = new LinkedHashMap<>(definitions(incoming));
        if (incoming instanceof DynamicRecord record) {
            var submitted = record.getPlatformValues().keySet();
            definitions.keySet().removeIf(field -> !submitted.contains(field));
        }
        return definitions;
    }

    @Override
    public <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) {
        existing = existingRecord(ability, existing, incoming);
        Map<String, FileReferenceDefinition> allDefinitions = writtenDefinitions(incoming);
        Map<String, FileReferenceDefinition> definitions = fileServerDefinitions(allDefinitions);
        Map<String, FileReferenceDefinition> inlineDefinitions = inlineDefinitions(allDefinitions);
        java.util.List<ResolvedFileDeletion> deletions = removedFileReferences(existing, incoming, definitions);
        if (!deletions.isEmpty()) pendingDeletions.get().put(incoming, deletions);
        if (definitions.isEmpty() && inlineDefinitions.isEmpty()) {
            return;
        }
        FileTransferClient client = clientSupplier.get();
        try {
            for (Map.Entry<String, FileReferenceDefinition> entry : definitions.entrySet()) {
                java.util.List<String> incomingFileIds = values(incoming, entry.getKey(), entry.getValue());
                java.util.Set<String> existingFileIds = new java.util.LinkedHashSet<>(
                        values(existing, entry.getKey(), entry.getValue()));
                java.util.List<String> newFileIds = incomingFileIds.stream()
                        .filter(fileId -> !existingFileIds.contains(fileId))
                        .toList();
                if (!newFileIds.isEmpty()) {
                    if (client == null) throw new PlatformException("file transfer client is not configured");
                    for (String fileId : newFileIds) {
                        requireBindingService().bind(incoming.getTenantId(), ability.getModuleAlias(), incoming.getId(),
                                entry.getKey(), fileId, entry.getValue());
                    }
                }
            }
            boolean inlineReferenceChanged = hasInlineReferenceValues(existing, incoming, inlineDefinitions);
            requireTransactionForInlineReferences(inlineReferenceChanged);
            if (inlineReferenceChanged) {
                pendingInlineReferences.get().put(incoming, inlineReferenceChange(incoming, inlineDefinitions));
            }
        } catch (RuntimeException failure) {
            persistFailed(ability, incoming, failure);
            throw failure;
        }
    }

    @Override
    public <T extends EntityContract> void persisted(CrudAbility<T> ability, T entity) {
        java.util.List<ResolvedFileDeletion> deletions = pendingDeletions.get().remove(entity);
        if (deletions != null && !deletions.isEmpty()) {
            TransactionScopeSupport.afterCommitOrNow(() -> deleteFiles(ability, entity, deletions));
        }
        InlineReferenceChange inlineChange = pendingInlineReferences.get().remove(entity);
        if (inlineChange != null) synchronizeInlineReferences(ability, entity, inlineChange);
        clearIfEmpty();
    }

    @Override
    public <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T entity, RuntimeException failure) {
        pendingDeletions.get().remove(entity);
        pendingInlineReferences.get().remove(entity);
        clearIfEmpty();
    }

    private FileReferenceBindingService requireBindingService() {
        FileReferenceBindingService service = bindingServiceSupplier.get();
        if (service == null) throw new PlatformException("file reference binding service is not configured");
        return service;
    }

    private Map<String, FileReferenceDefinition> definitions(EntityContract entity) {
        if (entity instanceof DynamicRecord record) return record.getEntity().fileReferences();
        Map<String, FileReferenceDefinition> values = new LinkedHashMap<>();
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                FileReference annotation = field.getAnnotation(FileReference.class);
                if (annotation != null) values.put(field.getName(), new FileReferenceDefinition(
                        java.util.Set.of(annotation.allowedMediaTypes()),
                        annotation.maxFileSizeBytes() > 0 ? annotation.maxFileSizeBytes() : null,
                        annotation.maxFiles(), Map.of(), annotation.storagePolicy()));
            }
        }
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                FileReferenceMetadataField binding = field.getAnnotation(FileReferenceMetadataField.class);
                if (binding == null) {
                    continue;
                }
                String source = binding.source() == null ? "" : binding.source().trim();
                FileReferenceDefinition definition = values.get(source);
                if (definition == null) {
                    throw new PlatformException("file reference metadata source must declare @FileReference: "
                            + entity.getClass().getName() + "." + field.getName());
                }
                if (definition.maxFiles() != 1) {
                    throw new PlatformException("file reference metadata fields require a single-file reference: " + source);
                }
                Map<FileReferenceMetadata, String> metadata = new EnumMap<>(FileReferenceMetadata.class);
                metadata.putAll(definition.metadataFields());
                if (metadata.put(binding.value(), field.getName()) != null) {
                    throw new PlatformException("duplicate file reference metadata binding: "
                            + source + "." + binding.value());
                }
                values.put(source, new FileReferenceDefinition(definition.allowedMediaTypes(),
                        definition.maxFileSizeBytes(), definition.maxFiles(), metadata, definition.storagePolicy()));
            }
        }
        return values;
    }

    private Map<String, FileReferenceDefinition> fileServerDefinitions(Map<String, FileReferenceDefinition> definitions) {
        Map<String, FileReferenceDefinition> values = new LinkedHashMap<>();
        definitions.forEach((field, definition) -> {
            if (definition.storagePolicy() == FileReferenceStoragePolicy.MUYUN_FILE_SERVER) values.put(field, definition);
        });
        return values;
    }

    private Map<String, FileReferenceDefinition> inlineDefinitions(Map<String, FileReferenceDefinition> definitions) {
        Map<String, FileReferenceDefinition> values = new LinkedHashMap<>();
        definitions.forEach((field, definition) -> {
            if (definition.storagePolicy() == FileReferenceStoragePolicy.DATABASE_INLINE) values.put(field, definition);
        });
        return values;
    }

    private void applyInlineMetadataFields(EntityContract incoming, EntityContract existing,
                                           Map<String, FileReferenceDefinition> definitions) {
        if (definitions.isEmpty()) return;
        ManagedFileAssetService service = managedAssetServiceSupplier.get();
        if (service == null) throw new PlatformException("managed file asset service is not configured");
        String ownerTenantId = ownerTenantId(incoming);
        for (Map.Entry<String, FileReferenceDefinition> entry : definitions.entrySet()) {
            java.util.List<String> ids = values(incoming, entry.getKey(), entry.getValue());
            Map<String, FileTransferFileMetadata> metadata = new LinkedHashMap<>();
            for (String id : ids) {
                FileTransferFileMetadata value = service.readReferenceMetadata(ownerTenantId, id);
                FileReferenceConfirmationService.validateMetadata(entry.getValue(), value);
                metadata.put(id, value);
            }
            applyMetadataFields(incoming, existing, entry.getKey(), entry.getValue(), ids, metadata);
        }
    }

    private InlineReferenceChange inlineReferenceChange(EntityContract incoming,
                                                        Map<String, FileReferenceDefinition> definitions) {
        Map<String, java.util.List<String>> current = new LinkedHashMap<>();
        for (Map.Entry<String, FileReferenceDefinition> entry : definitions.entrySet()) {
            java.util.List<String> next = values(incoming, entry.getKey(), entry.getValue());
            current.put(entry.getKey(), next);
        }
        return new InlineReferenceChange(Map.copyOf(current));
    }

    private <T extends EntityContract> void synchronizeInlineReferences(CrudAbility<T> ability, T entity,
                                                                         InlineReferenceChange change) {
        ManagedFileAssetReferenceService references = managedAssetReferenceServiceSupplier.get();
        if (references == null) throw new PlatformException("managed file asset reference service is not configured");
        String tenantId = ownerTenantId(entity);
        for (Map.Entry<String, java.util.List<String>> entry : change.currentByField().entrySet()) {
            references.replaceFieldReferences(tenantId, ability.getModuleAlias(), entity.getId(), entry.getKey(), entry.getValue());
        }
    }

    private boolean hasInlineReferenceValues(EntityContract existing, EntityContract incoming,
                                             Map<String, FileReferenceDefinition> definitions) {
        return definitions.entrySet().stream().anyMatch(entry ->
                !values(existing, entry.getKey(), entry.getValue()).isEmpty()
                        || !values(incoming, entry.getKey(), entry.getValue()).isEmpty());
    }

    private void requireTransactionForInlineReferences(boolean hasReferenceValues) {
        if (hasReferenceValues && !TransactionScopeSupport.isTransactionActive()) {
            throw new PlatformException("database inline file reference save requires an active transaction");
        }
    }

    private String ownerTenantId(EntityContract entity) {
        String tenantId = entity.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) return tenantId;
        if (entity.getId() != null && !entity.getId().isBlank()) return entity.getId();
        throw new PlatformException("database inline file reference requires a tenant owner");
    }

    private Object rawValue(EntityContract entity, String fieldName) {
        if (entity == null) return null;
        if (entity instanceof DynamicRecord record) return record.getValue(fieldName);
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.trySetAccessible();
                return field.get(entity);
            } catch (NoSuchFieldException ignored) {
                // Continue through inherited fields.
            } catch (IllegalAccessException failure) {
                throw new PlatformException("cannot read file reference field: " + fieldName, failure);
            }
        }
        throw new PlatformException("cannot find file reference field: " + fieldName);
    }

    private java.util.List<String> values(EntityContract entity, String fieldName, FileReferenceDefinition definition) {
        Object value = rawValue(entity, fieldName);
        if (value == null) return java.util.List.of();
        if (definition.maxFiles() == 1) {
            if (!(value instanceof String fileId)) {
                throw new PlatformException("single file reference value must be a string: " + fieldName);
            }
            return fileId.isBlank() ? java.util.List.of() : java.util.List.of(fileId.trim());
        }
        if (!(value instanceof java.util.Collection<?> collection)) {
            throw new PlatformException("multi-file reference value must be a collection: " + fieldName);
        }
        java.util.List<String> fileIds = new java.util.ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof String fileId) || fileId.isBlank()) {
                throw new PlatformException("multi-file reference must contain non-blank fileIds: " + fieldName);
            }
            fileIds.add(fileId.trim());
        }
        if (fileIds.size() > definition.maxFiles()) {
            throw new PlatformException("file reference exceeds max files: " + fieldName);
        }
        if (new java.util.LinkedHashSet<>(fileIds).size() != fileIds.size()) {
            throw new PlatformException("file reference must not contain duplicate fileIds: " + fieldName);
        }
        return fileIds;
    }

    /**
     * Metadata bindings are single-file snapshots. A multi-file field deliberately
     * has no implicit JSON representation: its business model must declare a
     * future collection snapshot contract explicitly instead of receiving a
     * platform-guessed shape.
     */
    private void applyMetadataFields(EntityContract incoming, EntityContract existing, String fileFieldName,
                                     FileReferenceDefinition definition, java.util.List<String> incomingFileIds,
                                     Map<String, FileTransferFileMetadata> preparedMetadata) {
        if (definition.metadataFields().isEmpty()) return;
        if (definition.maxFiles() != 1) {
            throw new PlatformException("file reference metadata fields require a single-file reference: " + fileFieldName);
        }
        FileTransferFileMetadata metadata = incomingFileIds.isEmpty() ? null : preparedMetadata.get(incomingFileIds.getFirst());
        for (Map.Entry<FileReferenceMetadata, String> binding : definition.metadataFields().entrySet()) {
            Object value = metadata == null
                    ? (incomingFileIds.isEmpty() ? null : rawValue(existing, binding.getValue()))
                    : metadataValue(metadata, binding.getKey());
            writeValue(incoming, binding.getValue(), value);
        }
    }

    private Object metadataValue(FileTransferFileMetadata metadata, FileReferenceMetadata field) {
        return switch (field) {
            case ORIGINAL_FILENAME -> metadata.originalFilename();
            case EXTENSION -> metadata.extension();
            case MIME_TYPE -> metadata.mimeType();
            case SIZE_BYTES -> metadata.sizeBytes();
            case SHA256 -> metadata.sha256();
        };
    }

    private void writeValue(EntityContract entity, String fieldName, Object value) {
        if (entity instanceof DynamicRecord record) {
            record.putGeneratedValue(fieldName, value);
            return;
        }
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.trySetAccessible();
                field.set(entity, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Continue through inherited fields.
            } catch (IllegalAccessException | IllegalArgumentException failure) {
                throw new PlatformException("cannot write file reference metadata field: " + fieldName, failure);
            }
        }
        throw new PlatformException("cannot find file reference metadata field: " + fieldName);
    }

    private void clearIfEmpty() {
        if (pendingDeletions.get().isEmpty()) pendingDeletions.remove();
        if (pendingInlineReferences.get().isEmpty()) pendingInlineReferences.remove();
    }

    /**
     * Derives removed files solely from the persisted record and the incoming entity.
     * Every entity in a child aggregate receives the same lifecycle callback through
     * its own CrudAbility, so a child reference is handled by the child service rather
     * than being addressed through a transport-level relation path.
     */
    private java.util.List<ResolvedFileDeletion> removedFileReferences(EntityContract existing,
                                                                         EntityContract incoming,
                                                                         Map<String, FileReferenceDefinition> definitions) {
        if (existing == null || definitions.isEmpty()) return java.util.List.of();
        java.util.List<ResolvedFileDeletion> deletions = new java.util.ArrayList<>();
        for (Map.Entry<String, FileReferenceDefinition> entry : definitions.entrySet()) {
            java.util.Set<String> next = new java.util.LinkedHashSet<>(values(incoming, entry.getKey(), entry.getValue()));
            for (String previousFileId : values(existing, entry.getKey(), entry.getValue())) {
                if (!next.contains(previousFileId)) {
                    deletions.add(new ResolvedFileDeletion(entry.getKey(), previousFileId));
                }
            }
        }
        return java.util.List.copyOf(deletions);
    }

    private <T extends EntityContract> void deleteFiles(CrudAbility<T> ability, T entity,
                                                         java.util.List<ResolvedFileDeletion> deletions) {
        FileTransferClient client = clientSupplier.get();
        if (client == null) {
            log.error("Business record saved but old file deletion cannot run because file transfer client is unavailable: moduleAlias={}, recordId={}, fileIds={}",
                    ability.getModuleAlias(), entity.getId(), deletions);
            return;
        }
        for (ResolvedFileDeletion deletion : deletions) {
            try {
                client.delete(deletion.fileId());
            } catch (RuntimeException failure) {
                log.error("Business record saved but old file deletion failed: moduleAlias={}, recordId={}, fileId={}",
                        ability.getModuleAlias(), entity.getId(), deletion.fileId(), failure);
            }
        }
    }

    private record ResolvedFileDeletion(String fieldName, String fileId) {
    }

    private record InlineReferenceChange(Map<String, java.util.List<String>> currentByField) {
    }

}
