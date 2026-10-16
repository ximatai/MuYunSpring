package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

@Service
public class PlatformMetadataEntityDefinitionCompiler {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;
    private final MetadataFieldDefinitionCompiler fieldDefinitionCompiler;

    public PlatformMetadataEntityDefinitionCompiler(MetadataService metadataService,
                                                    MetadataFieldService fieldService,
                                                    MetadataFieldDefinitionCompiler fieldDefinitionCompiler) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService must not be null");
        this.fieldService = Objects.requireNonNull(fieldService, "fieldService must not be null");
        this.fieldDefinitionCompiler = Objects.requireNonNull(fieldDefinitionCompiler,
                "fieldDefinitionCompiler must not be null");
    }

    public EntityDefinition compile(String metadataId) {
        Metadata metadata = metadataId == null || metadataId.isBlank() ? null : metadataService.select(metadataId);
        if (metadata == null) {
            throw new PlatformException("Metadata schema ensure requires existing metadata: " + metadataId);
        }
        return compile(metadata);
    }

    public EntityDefinition compile(Metadata metadata) {
        if (metadata == null || metadata.getId() == null || metadata.getId().isBlank()) {
            throw new PlatformException("Metadata schema ensure requires persisted metadata");
        }
        List<MetadataField> metadataFields = metadataFields(metadata.getId());
        List<FieldDefinition> compiledFields = metadataFields.stream()
                .filter(field -> !MetadataSystemFieldCatalog.isRuntimeReserved(field))
                .map(fieldDefinitionCompiler::compile)
                .toList();
        MetadataCapabilityResolution capabilityResolution = MetadataCapabilityCatalog.resolve(metadata, RelationRole.MAIN,
                metadataFields);
        List<FieldDefinition> fields = MetadataCapabilityCatalog.mergeDeclaredMetadataFields(capabilityResolution, compiledFields);
        return new EntityDefinition(
                metadata.getAlias(),
                metadata.getSchemaName(),
                metadata.getTableName(),
                metadata.getTitle(),
                fields,
                capabilities(metadata, capabilityResolution, fields),
                List.of(),
                List.of(),
                metadata.getSortPartitionFields() == null ? List.of() : List.copyOf(metadata.getSortPartitionFields())
        );
    }

    private List<MetadataField> metadataFields(String metadataId) {
        return fieldService.list(
                        Criteria.of().eq("metadataId", metadataId),
                        ALL,
                        Sort.asc(PlatformAbilityFields.SORT_FIELD)
                );
    }

    private EnumSet<EntityCapability> capabilities(Metadata metadata, MetadataCapabilityResolution capabilityResolution,
                                                    List<FieldDefinition> fields) {
        EnumSet<EntityCapability> capabilities = EnumSet.of(EntityCapability.CRUD);
        for (FieldDefinition field : fields) {
            if (field.isTitle()) {
                capabilities.add(EntityCapability.REFERENCE);
            }
        }
        capabilities.addAll(capabilityResolution.capabilities());
        if (Boolean.TRUE.equals(metadata.getDataScopeEnabled())) capabilities.add(EntityCapability.DATA_SCOPE);
        return capabilities;
    }


}
