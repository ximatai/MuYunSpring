package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.EntitySaveLifecycleListener;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.file.FileReference;
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
    private final ThreadLocal<Map<EntityContract, Map<String, String>>> promoted =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private final ThreadLocal<Set<EntityContract>> transactionTracked =
            ThreadLocal.withInitial(() -> java.util.Collections.newSetFromMap(new IdentityHashMap<>()));

    public FileReferenceSaveLifecycleListener(Supplier<FileTransferClient> clientSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier must not be null");
    }

    @Override
    public <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) {
        if (existing == null && incoming.getId() != null && !incoming.getId().isBlank()) {
            existing = ability.selectActiveRaw(incoming.getId());
        }
        Map<String, FileReferenceDefinition> definitions = definitions(incoming);
        if (definitions.isEmpty()) return;
        FileTransferClient client = clientSupplier.get();
        try {
            for (Map.Entry<String, FileReferenceDefinition> entry : definitions.entrySet()) {
                String fileId = value(incoming, entry.getKey());
                if (fileId == null || fileId.isBlank() || Objects.equals(fileId, value(existing, entry.getKey()))) continue;
                if (client == null) throw new PlatformException("file transfer client is not configured");
                new FileReferenceConfirmationService(client).confirmAndPromote(entry.getValue(), fileId);
                promoted.get().computeIfAbsent(incoming, ignored -> new LinkedHashMap<>()).put(entry.getKey(), fileId);
            }
            trackTransaction(ability, incoming);
        } catch (RuntimeException failure) {
            persistFailed(ability, incoming, failure);
            throw failure;
        }
    }

    @Override
    public <T extends EntityContract> void persisted(CrudAbility<T> ability, T entity) {
        if (transactionTracked.get().contains(entity)) return;
        promoted.get().remove(entity);
        clearIfEmpty();
    }

    @Override
    public <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T entity, RuntimeException failure) {
        Map<String, String> fileIds = promoted.get().remove(entity);
        if (fileIds != null && !fileIds.isEmpty()) {
            log.error("File reference was promoted but business persistence failed: moduleAlias={}, recordId={}, fileIds={}",
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
                        annotation.maxFileSizeBytes() > 0 ? annotation.maxFileSizeBytes() : null));
            }
        }
        return values;
    }

    private String value(EntityContract entity, String fieldName) {
        if (entity == null) return null;
        if (entity instanceof DynamicRecord record) return text(record.getValue(fieldName));
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.trySetAccessible();
                return text(field.get(entity));
            } catch (NoSuchFieldException ignored) {
                // Continue through inherited fields.
            } catch (IllegalAccessException failure) {
                throw new PlatformException("cannot read file reference field: " + fieldName, failure);
            }
        }
        throw new PlatformException("cannot find file reference field: " + fieldName);
    }

    private String text(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    private void clearIfEmpty() { if (promoted.get().isEmpty()) promoted.remove(); }

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
