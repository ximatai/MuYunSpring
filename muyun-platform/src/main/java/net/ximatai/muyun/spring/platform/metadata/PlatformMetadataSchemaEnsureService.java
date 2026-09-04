package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import org.springframework.stereotype.Service;

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
        TransactionScopeSupport.afterCommitOrNow(() -> ensureNow(metadataId));
    }

    public void ensure(Metadata metadata) {
        TransactionScopeSupport.afterCommitOrNow(() -> ensureNow(metadata));
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
