package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.LinkedHashSet;
import java.util.Set;

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
        private PlatformMetadataSchemaEnsureService owner() { return PlatformMetadataSchemaEnsureService.this; }
        @Override public void beforeCommit(boolean readOnly) {
            // All field writes are visible; a schema failure still aborts the configuration transaction.
            metadataIds.forEach(PlatformMetadataSchemaEnsureService.this::ensureNow);
        }
    }

    public boolean ensureNow(String metadataId) {
        return ensureNow(compiler.compile(metadataId));
    }

    public boolean ensureNow(Metadata metadata) {
        return ensureNow(compiler.compile(metadata));
    }

    /** Reconciles a changed definition with its previous shape, including safe column removal. */
    public boolean ensureNow(String metadataId, EntityDefinition previousEntity) {
        return schemaService.ensureTable(compiler.compile(metadataId), previousEntity, null).isChanged();
    }

    public void dropNow(Metadata metadata) {
        schemaService.dropTable(compiler.compile(metadata));
    }

    public boolean ensureNow(EntityDefinition entity) {
        return schemaService.ensureTable(entity);
    }
}
