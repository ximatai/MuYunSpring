package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.EntitySaveLifecycleListener;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.mutation.RecordFileDeletionIntent;
import net.ximatai.muyun.spring.common.mutation.RecordSaveMutationMetadataContext;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.Supplier;

/** Applies the same file-reference lifecycle to static models and dynamic records. */
public final class FileReferenceSaveLifecycleListener implements EntitySaveLifecycleListener {
    private static final Logger log = LoggerFactory.getLogger(FileReferenceSaveLifecycleListener.class);
    private final Supplier<FileTransferClient> clientSupplier;
    private final ThreadLocal<Map<EntityContract, Map<String, java.util.List<String>>>> promoted =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private final ThreadLocal<Set<EntityContract>> transactionTracked =
            ThreadLocal.withInitial(() -> java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    private final ThreadLocal<Map<EntityContract, java.util.List<ResolvedFileDeletion>>> pendingDeletions =
            ThreadLocal.withInitial(IdentityHashMap::new);

    public FileReferenceSaveLifecycleListener(Supplier<FileTransferClient> clientSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier must not be null");
    }

    @Override
    public <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) {
        if (existing == null && incoming.getId() != null && !incoming.getId().isBlank()) {
            existing = ability.selectActiveRaw(incoming.getId());
        }
        Map<String, FileReferenceDefinition> definitions = definitions(incoming);
        java.util.List<ResolvedFileDeletion> deletions = validateDeletionIntents(ability, existing, incoming, definitions);
        if (!deletions.isEmpty()) pendingDeletions.get().put(incoming, deletions);
        if (definitions.isEmpty()) {
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
                if (newFileIds.isEmpty()) continue;
                if (client == null) throw new PlatformException("file transfer client is not configured");
                for (String fileId : newFileIds) {
                    new FileReferenceConfirmationService(client).confirmAndPromote(entry.getValue(), fileId);
                }
                promoted.get().computeIfAbsent(incoming, ignored -> new LinkedHashMap<>()).put(entry.getKey(), newFileIds);
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
        if (transactionTracked.get().contains(entity)) return;
        promoted.get().remove(entity);
        clearIfEmpty();
    }

    @Override
    public <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T entity, RuntimeException failure) {
        pendingDeletions.get().remove(entity);
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
                        annotation.maxFiles()));
            }
        }
        return values;
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

    private void clearIfEmpty() {
        if (promoted.get().isEmpty()) promoted.remove();
        if (pendingDeletions.get().isEmpty()) pendingDeletions.remove();
    }

    private <T extends EntityContract> java.util.List<ResolvedFileDeletion> validateDeletionIntents(CrudAbility<T> ability,
                                                                                                      EntityContract existing,
                                                                                                      EntityContract incoming,
                                                                                                      Map<String, FileReferenceDefinition> definitions) {
        java.util.List<RecordFileDeletionIntent> intents = RecordSaveMutationMetadataContext.current()
                .map(metadata -> metadata.fileDeletions()).orElse(java.util.List.of());
        if (intents.isEmpty()) return java.util.List.of();
        if (existing == null || existing.getId() == null || existing.getId().isBlank()) {
            throw new PlatformException("file deletion intents require an existing record");
        }
        java.util.List<ResolvedFileDeletion> deletions = new java.util.ArrayList<>();
        java.util.Set<RecordFileDeletionIntent> resolved = new java.util.LinkedHashSet<>();
        for (RecordFileDeletionIntent intent : intents) {
            String targetId = intent.recordPath().nodes().getFirst().recordId();
            if (!Objects.equals(targetId, existing.getId())) continue;
            ResolvedFileDeletion deletion = switch (intent.recordPath().nodes().size()) {
                case 1 -> resolveDeletion(intent, existing, incoming, definitions);
                case 2 -> resolveChildDeletion(ability, intent, existing, incoming);
                default -> throw new PlatformException("file deletion path supports at most one child relation");
            };
            if (!resolved.add(intent)) {
                throw new PlatformException("duplicate file deletion intent: " + intent.fileId());
            }
            deletions.add(deletion);
        }
        return java.util.List.copyOf(deletions);
    }

    private ResolvedFileDeletion resolveDeletion(RecordFileDeletionIntent intent,
                                                 EntityContract existing,
                                                 EntityContract incoming,
                                                 Map<String, FileReferenceDefinition> definitions) {
        if (!definitions.containsKey(intent.fieldName())) {
            throw new PlatformException("file deletion field is not a file reference: " + intent.fieldName());
        }
        FileReferenceDefinition definition = definitions.get(intent.fieldName());
        java.util.List<String> previousFileIds = values(existing, intent.fieldName(), definition);
        java.util.List<String> nextFileIds = values(incoming, intent.fieldName(), definition);
        if (!previousFileIds.contains(intent.fileId())) {
            throw new PlatformException("file deletion does not match existing reference: " + intent.fieldName());
        }
        if (nextFileIds.contains(intent.fileId())) {
            throw new PlatformException("file deletion requires removing or replacing the referenced file: "
                    + intent.fieldName());
        }
        return new ResolvedFileDeletion(intent.fieldName(), intent.fileId());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResolvedFileDeletion resolveChildDeletion(CrudAbility<?> ability,
                                                       RecordFileDeletionIntent intent,
                                                       EntityContract existingParent,
                                                       EntityContract incomingParent) {
        if (!(ability instanceof ChildrenAbility childrenAbility)) {
            throw new PlatformException("file deletion path does not support child relations: " + intent.recordPath());
        }
        String relationCode = intent.recordPath().nodes().get(1).relationCode();
        String childId = intent.recordPath().nodes().get(1).recordId();
        java.util.List<ChildRelation<? extends EntityContract, EntityContract>> relations =
                (java.util.List<ChildRelation<? extends EntityContract, EntityContract>>) (java.util.List<?>) childrenAbility.childRelations();
        ChildRelation relation = null;
        for (ChildRelation<?, ?> candidate : relations) {
            if (Objects.equals(candidate.relationCode(), relationCode)) {
                relation = candidate;
                break;
            }
        }
        if (relation == null) {
            throw new PlatformException("unknown child relation for file deletion: " + relationCode);
        }
        EntityContract existingChild = null;
        for (Object candidate : relation.selectChildren(existingParent.getId())) {
            if (candidate instanceof EntityContract entity && Objects.equals(entity.getId(), childId)) {
                existingChild = entity;
                break;
            }
        }
        if (existingChild == null) {
            throw new PlatformException("file deletion child does not belong to parent: " + childId);
        }
        java.util.List<?> incomingChildren = relation.incomingChildren(incomingParent);
        if (incomingChildren == null) {
            throw new PlatformException("file deletion child must remain in the save payload: " + childId);
        }
        EntityContract incomingChild = null;
        for (Object candidate : incomingChildren) {
            if (candidate instanceof EntityContract entity && Objects.equals(entity.getId(), childId)) {
                incomingChild = entity;
                break;
            }
        }
        if (incomingChild == null) {
            throw new PlatformException("file deletion child must remain in the save payload: " + childId);
        }
        return resolveDeletion(intent, existingChild, incomingChild, definitions(incomingChild));
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
