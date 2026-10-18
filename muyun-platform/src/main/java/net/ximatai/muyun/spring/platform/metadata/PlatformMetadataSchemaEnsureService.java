package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

import java.util.Objects;

@Service
public class PlatformMetadataSchemaEnsureService {
    private final PlatformMetadataEntityDefinitionCompiler compiler;
    private final DynamicSchemaService schemaService;

    public PlatformMetadataSchemaEnsureService(PlatformMetadataEntityDefinitionCompiler compiler,
                                               DynamicSchemaService schemaService) {
        this.compiler = Objects.requireNonNull(compiler, "compiler must not be null");
        this.schemaService = Objects.requireNonNull(schemaService, "schemaService must not be null");
    }

    public void ensure(String metadataId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            ensureNow(metadataId);
            return;
        }
        // Inspect the current transaction's synchronizations, avoiding thread-local state that can leak into REQUIRES_NEW.
        SchemaEnsureSynchronization pending = TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(sync -> sync instanceof SchemaEnsureSynchronization candidate && candidate.owner() == this)
                .map(SchemaEnsureSynchronization.class::cast).findFirst().orElseGet(() -> {
                    var created = new SchemaEnsureSynchronization();
                    TransactionSynchronizationManager.registerSynchronization(created);
                    return created;
                });
        pending.metadataIds.add(metadataId);
    }

    public void ensure(Metadata metadata) {
        if (metadata.getId() == null) ensureNow(metadata);
        else ensure(metadata.getId());
    }

    private final class SchemaEnsureSynchronization implements TransactionSynchronization {
        private final Set<String> metadataIds = new LinkedHashSet<>();
        private final Map<Object, Set<String>> savepoints = new LinkedHashMap<>();
        private PlatformMetadataSchemaEnsureService owner() { return PlatformMetadataSchemaEnsureService.this; }
        @Override public void savepoint(Object savepoint) {
            savepoints.put(savepoint, new LinkedHashSet<>(metadataIds));
        }
        @Override public void savepointRollback(Object savepoint) {
            var pending = savepoints.get(savepoint);
            if (pending == null) return;
            metadataIds.clear();
            metadataIds.addAll(pending);
            boolean after = false;
            var snapshots = savepoints.keySet().iterator();
            while (snapshots.hasNext()) {
                Object current = snapshots.next();
                if (after) snapshots.remove();
                if (current.equals(savepoint)) after = true;
            }
        }
        @Override public void beforeCommit(boolean readOnly) {
            // All field writes are visible; a schema failure still aborts the configuration transaction.
            new LinkedHashSet<>(metadataIds).forEach(PlatformMetadataSchemaEnsureService.this::ensureNow);
        }
    }

    public boolean ensureNow(String metadataId) {
        boolean changed = ensureNow(compiler.compile(metadataId));
        markEnsured(metadataId);
        return changed;
    }

    public boolean ensureNow(Metadata metadata) {
        boolean changed = ensureNow(compiler.compile(metadata));
        markEnsured(metadata.getId());
        return changed;
    }

    /** Reconciles a changed definition with its previous shape, including safe column removal. */
    public boolean ensureNow(String metadataId, EntityDefinition previousEntity) {
        boolean changed = schemaService.ensureTable(compiler.compile(metadataId), previousEntity, null).isChanged();
        markEnsured(metadataId);
        return changed;
    }

    private void markEnsured(String metadataId) {
        if (metadataId == null || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        // Explicit publication has reconciled all writes so far. A later write registers a fresh
        // request; replaying the earlier request can rediscover uncommitted DDL through a stale cache.
        TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(sync -> sync instanceof SchemaEnsureSynchronization candidate && candidate.owner() == this)
                .map(SchemaEnsureSynchronization.class::cast)
                .forEach(pending -> pending.metadataIds.remove(metadataId));
    }

    public void dropNow(Metadata metadata) {
        schemaService.dropTable(compiler.compile(metadata));
    }

    public boolean ensureNow(EntityDefinition entity) {
        return schemaService.ensureTable(entity);
    }
}
