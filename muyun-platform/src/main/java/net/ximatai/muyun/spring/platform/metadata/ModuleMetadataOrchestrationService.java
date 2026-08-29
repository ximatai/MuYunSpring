package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.LinkedHashSet;

/**
 * Module-scoped metadata authoring operations.
 *
 * <p>The service deliberately owns the transaction spanning Metadata and its module relation so
 * callers cannot leave a dynamic module with an unbound half-created entity.</p>
 */
@Service
public class ModuleMetadataOrchestrationService {
    private final PlatformModuleService moduleService;
    private final MetadataService metadataService;
    private final ModuleMetadataRelationService relationService;

    public ModuleMetadataOrchestrationService(PlatformModuleService moduleService,
                                              MetadataService metadataService,
                                              ModuleMetadataRelationService relationService) {
        this.moduleService = Objects.requireNonNull(moduleService, "moduleService must not be null");
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService must not be null");
        this.relationService = Objects.requireNonNull(relationService, "relationService must not be null");
    }

    @Transactional
    public ModuleMainMetadataCreationResult createMainMetadata(String moduleAlias,
                                                                ModuleMainMetadataCreateCommand command) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (command == null) {
            throw new IllegalArgumentException("main metadata create command must not be null");
        }
        PlatformModule module = moduleService.select(validModuleAlias);
        if (module == null) {
            throw new PlatformException("Metadata orchestration requires existing module: " + validModuleAlias);
        }
        if (module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new PlatformException("Metadata orchestration only supports dynamic module: " + validModuleAlias);
        }
        if (relationService.count(Criteria.of()
                .eq("moduleAlias", validModuleAlias)
                .eq("relationRole", RelationRole.MAIN)) > 0) {
            throw new PlatformException("Module already has MAIN metadata relation: " + validModuleAlias);
        }

        Metadata metadata = new Metadata();
        metadata.setApplicationAlias(module.getApplicationAlias());
        metadata.setAlias(command.alias());
        metadata.setTitle(command.title());
        metadata.setSchemaName(command.schemaName());
        metadata.setTableName(command.tableName());
        metadata.setDataScopeEnabled(command.dataScopeEnabled());
        // A newly created dynamic model is governed from its first save. Null is
        // reserved for legacy metadata whose capability state still needs migration.
        metadata.setCapabilityDeclarations(new LinkedHashSet<>());
        String metadataId = MetadataCapabilityGovernanceMutationContext.run(() -> metadataService.insert(metadata));

        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(validModuleAlias);
        relation.setMetadataId(metadataId);
        relation.setRelationRole(RelationRole.MAIN);
        relation.setRelationAlias(metadata.getAlias());
        relation.setTitle(metadata.getTitle());
        String relationId = relationService.insert(relation);
        return new ModuleMainMetadataCreationResult(metadataService.select(metadataId), relationService.select(relationId));
    }
}
