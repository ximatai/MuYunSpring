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
import java.util.Set;
import java.util.Objects;
import java.util.function.Supplier;

/** Applies the same file-reference lifecycle to static models and dynamic records. */
public final class FileReferenceSaveLifecycleListener implements EntitySaveLifecycleListener {
    private static final Logger log = LoggerFactory.getLogger(FileReferenceSaveLifecycleListener.class);
    private final Supplier<FileTransferClient> clientSupplier;
    private final Supplier<ManagedFileAssetService> managedAssetServiceSupplier;
    private final Supplier<ManagedFileAssetReferenceService> managedAssetReferenceServiceSupplier;
    private final ThreadLocal<Map<EntityContract, Map<String, java.util.List<String>>>> promoted =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private final ThreadLocal<Set<EntityContract>> transactionTracked =
            ThreadLocal.withInitial(() -> java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    private final ThreadLocal<Map<EntityContract, java.util.List<ResolvedFileDeletion>>> pendingDeletions =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private final ThreadLocal<Map<EntityContract, InlineReferenceChange>> pendingInlineReferences =
            ThreadLocal.withInitial(IdentityHashMap::new);

    public FileReferenceSaveLifecycleListener(Supplier<FileTransferClient> clientSupplier) {
        this(clientSupplier, () -> null, () -> null);
    }

    public FileReferenceSaveLifecycleListener(Supplier<FileTransferClient> clientSupplier,
                                              Supplier<ManagedFileAssetService> managedAssetServiceSupplier) {
        this(clientSupplier, managedAssetServiceSupplier, () -> null);
    }

    public FileReferenceSaveLifecycleListener(Supplier<FileTransferClient> clientSupplier,
                                              Supplier<ManagedFileAssetService> managedAssetServiceSupplier,
                                              Supplier<ManagedFileAssetReferenceService> managedAssetReferenceServiceSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier must not be null");
        this.managedAssetServiceSupplier = Objects.requireNonNull(managedAssetServiceSupplier,
                "managedAssetServiceSupplier must not be null");
        this.managedAssetReferenceServiceSupplier = Objects.requireNonNull(managedAssetReferenceServiceSupplier,
                "managedAssetReferenceServiceSupplier must not be null");
    }

    @Override
    public <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) {
        if (existing == null && incoming.getId() != null && !incoming.getId().isBlank()) {
            existing = ability.selectActiveRaw(incoming.getId());
        }
        Map<String, FileReferenceDefinition> allDefinitions = definitions(incoming);
        Map<String, FileReferenceDefinition> definitions = fileServerDefinitions(allDefinitions);
        Map<String, FileReferenceDefinition> inlineDefinitions = inlineDefinitions(allDefinitions);
        java.util.List<ResolvedFileDeletion> deletions = removedFileReferences(existing, incoming, definitions);
        if (!deletions.isEmpty()) pendingDeletions.get().put(incoming, deletions);
        if (definitions.isEmpty() && inlineDefinitions.isEmpty()) {
            if (!deletions.isEmpty()) trackTransaction(ability, incoming);
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
                Map<String, FileTransferFileMetadata> promotedMetadata = new LinkedHashMap<>();
                if (!newFileIds.isEmpty()) {
                    if (client == null) throw new PlatformException("file transfer client is not configured");
                    for (String fileId : newFileIds) {
                        promotedMetadata.put(fileId,
                                new FileReferenceConfirmationService(client).confirmAndPromote(entry.getValue(), fileId));
                        promoted.get().computeIfAbsent(incoming, ignored -> new LinkedHashMap<>())
                                .computeIfAbsent(entry.getKey(), ignored -> new java.util.ArrayList<>())
                                .add(fileId);
                    }
                }
                applyMetadataFields(incoming, existing, entry.getKey(), entry.getValue(), incomingFileIds, promotedMetadata);
            }
            applyInlineMetadataFields(incoming, existing, inlineDefinitions);
            if (!inlineDefinitions.isEmpty()) {
                pendingInlineReferences.get().put(incoming, inlineReferenceChange(existing, incoming, inlineDefinitions));
            }
            trackTransaction(ability, incoming);
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
        if (transactionTracked.get().contains(entity)) return;
        promoted.get().remove(entity);
        clearIfEmpty();
    }

    @Override
    public <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T entity, RuntimeException failure) {
        pendingDeletions.get().remove(entity);
        InlineReferenceChange inlineChange = pendingInlineReferences.get().remove(entity);
        if (inlineChange != null) deleteUnboundInlineAssets(entity, inlineChange.newAssetIds());
        Map<String, java.util.List<String>> fileIds = promoted.get().remove(entity);
        if (fileIds != null && !fileIds.isEmpty()) {
            log.error("File reference was promoted but record save did not complete: moduleAlias={}, recordId={}, fileIds={}",
                    ability.getModuleAlias(), entity.getId(), fileIds, failure);
        }
        clearIfEmpty();
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
                validateMetadata(entry.getValue(), value);
                metadata.put(id, value);
            }
            applyMetadataFields(incoming, existing, entry.getKey(), entry.getValue(), ids, metadata);
        }
    }

    private InlineReferenceChange inlineReferenceChange(EntityContract existing, EntityContract incoming,
                                                        Map<String, FileReferenceDefinition> definitions) {
        Map<String, java.util.List<String>> current = new LinkedHashMap<>();
        java.util.Set<String> removed = new java.util.LinkedHashSet<>();
        java.util.Set<String> added = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, FileReferenceDefinition> entry : definitions.entrySet()) {
            java.util.List<String> next = values(incoming, entry.getKey(), entry.getValue());
            java.util.Set<String> previous = new java.util.LinkedHashSet<>(values(existing, entry.getKey(), entry.getValue()));
            current.put(entry.getKey(), next);
            for (String id : previous) if (!next.contains(id)) removed.add(id);
            for (String id : next) if (!previous.contains(id)) added.add(id);
        }
        return new InlineReferenceChange(Map.copyOf(current), Set.copyOf(removed), Set.copyOf(added));
    }

    private <T extends EntityContract> void synchronizeInlineReferences(CrudAbility<T> ability, T entity,
                                                                         InlineReferenceChange change) {
        ManagedFileAssetReferenceService references = managedAssetReferenceServiceSupplier.get();
        ManagedFileAssetService assets = managedAssetServiceSupplier.get();
        if (references == null || assets == null) throw new PlatformException("managed file asset reference service is not configured");
        String tenantId = ownerTenantId(entity);
        for (Map.Entry<String, java.util.List<String>> entry : change.currentByField().entrySet()) {
            references.replaceFieldReferences(tenantId, ability.getModuleAlias(), entity.getId(), entry.getKey(), entry.getValue());
        }
        if (!change.removedAssetIds().isEmpty()) {
            TransactionScopeSupport.afterCommitOrNow(() -> {
                for (String assetId : change.removedAssetIds()) {
                    try {
                        assets.deleteOwnedIfUnreferenced(tenantId, assetId, references);
                    } catch (RuntimeException failure) {
                        log.error("Business record saved but replaced inline asset collection failed: moduleAlias={}, recordId={}, assetId={}",
                                ability.getModuleAlias(), entity.getId(), assetId, failure);
                    }
                }
            });
        }
    }

    private void deleteUnboundInlineAssets(EntityContract entity, Set<String> assetIds) {
        ManagedFileAssetReferenceService references = managedAssetReferenceServiceSupplier.get();
        ManagedFileAssetService assets = managedAssetServiceSupplier.get();
        if (references == null || assets == null) return;
        String tenantId = ownerTenantId(entity);
        for (String assetId : assetIds) {
            try {
                assets.deleteOwnedIfUnreferenced(tenantId, assetId, references);
            } catch (RuntimeException cleanupFailure) {
                log.error("Failed to collect unbound inline asset after record save failure: assetId={}", assetId, cleanupFailure);
            }
        }
    }

    private String ownerTenantId(EntityContract entity) {
        String tenantId = entity.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) return tenantId;
        if (entity.getId() != null && !entity.getId().isBlank()) return entity.getId();
        throw new PlatformException("database inline file reference requires a tenant owner");
    }

    private void validateMetadata(FileReferenceDefinition definition, FileTransferFileMetadata metadata) {
        if (!definition.allowedMediaTypes().isEmpty() && !definition.allowedMediaTypes().contains(metadata.mimeType())) {
            throw new PlatformException("file reference media type is not allowed: " + metadata.mimeType());
        }
        if (definition.maxFileSizeBytes() != null && metadata.sizeBytes() > definition.maxFileSizeBytes()) {
            throw new PlatformException("file reference exceeds max file size: " + metadata.fileId());
        }
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
                                     Map<String, FileTransferFileMetadata> promotedMetadata) {
        if (definition.metadataFields().isEmpty()) return;
        if (definition.maxFiles() != 1) {
            throw new PlatformException("file reference metadata fields require a single-file reference: " + fileFieldName);
        }
        FileTransferFileMetadata metadata = incomingFileIds.isEmpty() ? null : promotedMetadata.get(incomingFileIds.getFirst());
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
        if (promoted.get().isEmpty()) promoted.remove();
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

    private record InlineReferenceChange(Map<String, java.util.List<String>> currentByField,
                                         Set<String> removedAssetIds,
                                         Set<String> newAssetIds) {
    }

    private <T extends EntityContract> void trackTransaction(CrudAbility<T> ability, T entity) {
        if (!TransactionScopeSupport.isTransactionActive() || !transactionTracked.get().add(entity)) return;
        TransactionScopeSupport.afterCompletionOrNow(
                () -> { promoted.get().remove(entity); transactionTracked.get().remove(entity); clearIfEmpty(); },
                () -> {
                    persistFailed(ability, entity, new PlatformException("business transaction rolled back"));
                    transactionTracked.get().remove(entity);
                });
    }
}
