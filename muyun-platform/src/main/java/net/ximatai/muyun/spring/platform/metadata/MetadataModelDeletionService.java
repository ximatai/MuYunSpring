package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
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
    private final MetadataFieldConfigService fieldConfigService;
    private final MetadataFieldReferenceConfigService referenceConfigService;
    private final PlatformMetadataEntityDefinitionCompiler compiler;
    private final PlatformMetadataSchemaEnsureService schemaService;
    private final DynamicRecordService recordService;
    private final PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator;

    public MetadataModelDeletionService(ModuleMetadataRelationService relationService,
                                        MetadataService metadataService,
                                        MetadataFieldService fieldService,
                                        ModuleMetadataFieldService moduleFieldService,
                                        MetadataFieldConfigService fieldConfigService,
                                        MetadataFieldReferenceConfigService referenceConfigService,
                                        PlatformMetadataEntityDefinitionCompiler compiler,
                                        PlatformMetadataSchemaEnsureService schemaService,
                                        DynamicRecordService recordService,
                                        PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator) {
        this.relationService = Objects.requireNonNull(relationService);
        this.metadataService = Objects.requireNonNull(metadataService);
        this.fieldService = Objects.requireNonNull(fieldService);
        this.moduleFieldService = Objects.requireNonNull(moduleFieldService);
        this.fieldConfigService = Objects.requireNonNull(fieldConfigService);
        this.referenceConfigService = Objects.requireNonNull(referenceConfigService);
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-model-deletion.the-field-does-not-belong-to-the-current-metadata-node",
                    "字段不属于当前元数据节点：{value1}"), Map.of("value1", String.valueOf(fieldId)));
        }
        if (Boolean.TRUE.equals(field.getSystemManaged()) || field.getFieldOwnership() != MetadataFieldOwnership.BUSINESS
                || Objects.equals(relation.getForeignKey(), field.getFieldName())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-model-deletion.platform-fields-capability-fields-and-child-foreign-keys-cannot-be-deleted",
                    "平台字段、能力字段和子元数据外键不能删除：{value1}"), Map.of("value1", String.valueOf(field.getTitle())));
        }
        Metadata metadata = requireMetadata(relation);
        DynamicSchemaGovernanceFacts schemaFacts = recordService.schemaGovernanceFacts();
        long values = schemaFacts.lockExistingTableForSchemaMutation(metadata.getSchemaName(), metadata.getTableName())
                ? schemaFacts.countPhysicalRecords(moduleAlias, metadata.getAlias(),
                Criteria.of().isNotNull(field.getFieldName()))
                : 0L;
        if (values > 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-model-deletion.the-field-contains-business-data-and-cannot-be-deleted-record-count",
                    "字段“{value1}”已有 {value2} 条业务数据，不能删除。"), Map.of("value1", String.valueOf(field.getTitle()), "value2", String.valueOf(values)));
        }
        EntityDefinition previous = compiler.compile(metadata);
        MetadataCapabilityGovernanceMutationContext.run(() -> {
            deleteModuleFieldMappings(relation.getId(), field.getId());
            // These declarations belong to the field in this relation. Other relations and
            // inherited configuration remain references, so the field guard still protects them.
            MetadataFieldPropertyMutationContext.run(() -> {
                for (MetadataFieldConfig config : fieldConfigService.list(Criteria.of()
                        .eq("metadataFieldId", field.getId()).eq("relationId", relation.getId()), ALL)) {
                    fieldConfigService.delete(config.getId(), config.getVersion());
                }
                for (MetadataFieldReferenceConfig config : referenceConfigService.list(Criteria.of()
                        .eq("metadataFieldId", field.getId()).eq("relationId", relation.getId()), ALL)) {
                    referenceConfigService.delete(config.getId(), config.getVersion());
                }
                return null;
            });
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-model-deletion.metadata-has-child-metadata-and-cannot-be-deleted",
                    "元数据“{value1}”仍有子元数据，不能删除。"), Map.of("value1", String.valueOf(metadata.getTitle())));
        }
        DynamicSchemaGovernanceFacts schemaFacts = recordService.schemaGovernanceFacts();
        long records = schemaFacts.lockExistingTableForSchemaMutation(metadata.getSchemaName(), metadata.getTableName())
                ? schemaFacts.countPhysicalRecords(moduleAlias, metadata.getAlias(), Criteria.of())
                : 0L;
        if (records > 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-model-deletion.metadata-contains-business-data-and-cannot-be-deleted",
                    "元数据“{value1}”已有业务数据，不能删除。"), Map.of("value1", String.valueOf(metadata.getTitle())));
        }
        List<MetadataField> fields = fieldService.list(Criteria.of().eq("metadataId", metadata.getId()), ALL,
                Sort.asc(PlatformAbilityFields.SORT_FIELD));
        boolean hasBusinessField = fields
                .stream().anyMatch(field -> field.getFieldOwnership() == MetadataFieldOwnership.BUSINESS
                        && !Boolean.TRUE.equals(field.getSystemManaged()));
        if (hasBusinessField) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-model-deletion.delete-all-business-fields-before-deleting-the-metadata",
                    "请先删除该元数据下全部业务字段，再删除元数据。"));
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-model-deletion.the-metadata-node-does-not-belong-to-the-current-module",
                    "元数据节点不属于当前模块：{value1}"), Map.of("value1", String.valueOf(relationId)));
        }
        return relation;
    }

    private Metadata requireMetadata(ModuleMetadataRelation relation) {
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.metadata-model-deletion.the-metadata-node-no-longer-exists",
                "元数据节点已不存在：{value1}"), Map.of("value1", String.valueOf(relation.getMetadataId())));
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
        refreshCoordinator.scheduleModules(List.of(moduleAlias));
    }

    private void refreshAfterMetadataDelete(String moduleAlias, RelationRole relationRole) {
        refreshCoordinator.scheduleModules(List.of(moduleAlias));
    }
}
