package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicSchemaGovernanceFacts;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Destructive metadata operations, guarded by configuration and real business-data checks. */
@Service
public class MetadataModelDeletionService {
    private final ModuleMetadataRelationService relationService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;
    private final ModuleMetadataFieldService moduleFieldService;
    private final PlatformMetadataEntityDefinitionCompiler compiler;
    private final PlatformMetadataSchemaEnsureService schemaService;
    private final DynamicRecordService recordService;
    private final PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator;

    public MetadataModelDeletionService(ModuleMetadataRelationService relationService,
                                        MetadataService metadataService,
                                        MetadataFieldService fieldService,
                                        ModuleMetadataFieldService moduleFieldService,
                                        PlatformMetadataEntityDefinitionCompiler compiler,
                                        PlatformMetadataSchemaEnsureService schemaService,
                                        DynamicRecordService recordService,
                                        PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator) {
        this.relationService = Objects.requireNonNull(relationService);
        this.metadataService = Objects.requireNonNull(metadataService);
        this.fieldService = Objects.requireNonNull(fieldService);
        this.moduleFieldService = Objects.requireNonNull(moduleFieldService);
        this.compiler = Objects.requireNonNull(compiler);
        this.schemaService = Objects.requireNonNull(schemaService);
        this.recordService = Objects.requireNonNull(recordService);
        this.refreshCoordinator = Objects.requireNonNull(refreshCoordinator);
    }

    @Transactional
    public void deleteField(String moduleAlias, String relationId, String fieldId) {
        ModuleMetadataRelation relation = relation(moduleAlias, relationId);
        MetadataField field = fieldService.select(fieldId);
        if (field == null || !Objects.equals(field.getMetadataId(), relation.getMetadataId())) {
            throw new PlatformException("字段不属于当前元数据节点：" + fieldId);
        }
        if (Boolean.TRUE.equals(field.getSystemManaged()) || field.getFieldOwnership() != MetadataFieldOwnership.BUSINESS
                || Objects.equals(relation.getForeignKey(), field.getFieldName())) {
            throw new PlatformException("平台字段、能力字段和子实体外键不能删除：" + field.getTitle());
        }
        Metadata metadata = requireMetadata(relation);
        DynamicSchemaGovernanceFacts schemaFacts = recordService.schemaGovernanceFacts();
        long values = schemaFacts.lockExistingTableForSchemaMutation(metadata.getSchemaName(), metadata.getTableName())
                ? schemaFacts.countPhysicalRecords(moduleAlias, metadata.getAlias(),
                Criteria.of().isNotNull(field.getFieldName()))
                : 0L;
        if (values > 0) {
            throw new PlatformException("字段“" + field.getTitle() + "”已有 " + values + " 条业务数据，不能删除。");
        }
        EntityDefinition previous = compiler.compile(metadata);
        MetadataCapabilityGovernanceMutationContext.run(() -> {
            deleteModuleFieldMappings(relation.getId(), field.getId());
            fieldService.delete(field.getId(), field.getVersion());
            return null;
        });
        schemaService.ensureNow(metadata.getId(), previous);
        activateAfterCommit(moduleAlias);
    }

    @Transactional
    public void deleteMetadata(String moduleAlias, String relationId) {
        ModuleMetadataRelation relation = relation(moduleAlias, relationId);
        Metadata metadata = requireMetadata(relation);
        if (relationService.count(Criteria.of().eq("parentMetadataId", metadata.getId()).eq("relationRole", RelationRole.CHILD)) > 0) {
            throw new PlatformException("元数据“" + metadata.getTitle() + "”仍有子元数据，不能删除。");
        }
        DynamicSchemaGovernanceFacts schemaFacts = recordService.schemaGovernanceFacts();
        long records = schemaFacts.lockExistingTableForSchemaMutation(metadata.getSchemaName(), metadata.getTableName())
                ? schemaFacts.countPhysicalRecords(moduleAlias, metadata.getAlias(), Criteria.of())
                : 0L;
        if (records > 0) {
            throw new PlatformException("元数据“" + metadata.getTitle() + "”已有业务数据，不能删除。");
        }
        List<MetadataField> fields = fieldService.list(Criteria.of().eq("metadataId", metadata.getId()), ALL,
                Sort.asc(PlatformAbilityFields.SORT_FIELD));
        boolean hasBusinessField = fields
                .stream().anyMatch(field -> field.getFieldOwnership() == MetadataFieldOwnership.BUSINESS
                        && !Boolean.TRUE.equals(field.getSystemManaged()));
        if (hasBusinessField) {
            throw new PlatformException("请先删除该元数据下全部业务字段，再删除元数据。");
        }
        MetadataCapabilityGovernanceMutationContext.run(() -> {
            deleteModuleFieldMappings(relation.getId(), null);
            relationService.delete(relation.getId(), relation.getVersion());
            for (MetadataField field : fields) {
                if (Boolean.TRUE.equals(field.getSystemManaged())) {
                    PlatformManagedMutationContext.runAsPlatformManaged(
                            () -> fieldService.delete(field.getId(), field.getVersion()));
                } else {
                    fieldService.delete(field.getId(), field.getVersion());
                }
            }
            metadataService.delete(metadata.getId(), metadata.getVersion());
            return null;
        });
        schemaService.dropNow(metadata);
        refreshAfterMetadataDelete(moduleAlias, relation.getRelationRole());
    }

    private ModuleMetadataRelation relation(String moduleAlias, String relationId) {
        ModuleMetadataRelation relation = relationService.select(relationId);
        if (relation == null || !Objects.equals(moduleAlias, relation.getModuleAlias())) {
            throw new PlatformException("元数据节点不属于当前模块：" + relationId);
        }
        return relation;
    }

    private Metadata requireMetadata(ModuleMetadataRelation relation) {
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) throw new PlatformException("元数据节点已不存在：" + relation.getMetadataId());
        return metadata;
    }

    /**
     * A module-field record is a runtime projection, not a user configuration by itself.
     * Deleting it first lets its own deletion guard reject real UI or rule references with
     * their concrete resource names, while allowing an otherwise unused business field to go.
     */
    private void deleteModuleFieldMappings(String relationId, String metadataFieldId) {
        Criteria criteria = Criteria.of().eq("relationId", relationId);
        if (metadataFieldId != null) {
            criteria.eq("metadataFieldId", metadataFieldId);
        }
        for (ModuleMetadataField moduleField : moduleFieldService.list(criteria, ALL,
                Sort.asc(PlatformAbilityFields.SORT_FIELD))) {
            moduleFieldService.delete(moduleField.getId(), moduleField.getVersion());
        }
    }

    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private void activateAfterCommit(String moduleAlias) {
        TransactionScopeSupport.afterCommitOrNow(() -> refreshCoordinator.activateModulesNow(List.of(moduleAlias)));
    }

    private void refreshAfterMetadataDelete(String moduleAlias, RelationRole relationRole) {
        TransactionScopeSupport.afterCommitOrNow(() -> {
            if (relationRole == RelationRole.MAIN) {
                refreshCoordinator.deactivateModulesNow(List.of(moduleAlias));
            } else {
                refreshCoordinator.activateModulesNow(List.of(moduleAlias));
            }
        });
    }
}
