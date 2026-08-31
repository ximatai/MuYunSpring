package net.ximatai.muyun.spring.platform.metadata;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.util.Arrays;
import java.util.List;

@Getter
@Setter
@Table(name = "platform_metadata_field_reference_config", comment = "Metadata field reference config")
@CompositeIndex(columns = {"metadata_field_id", "relation_id"}, unique = true)
public class MetadataFieldReferenceConfig extends StandardEntity {
    @Column(name = "metadata_field_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Metadata field id")
    private String metadataFieldId;

    @Column(name = "relation_id", type = ColumnType.VARCHAR, length = 32, comment = "Module metadata relation id")
    private String relationId;

    /**
     * Dynamic targets use their platform module alias; static targets use the complete platform
     * module alias that owns the registered static service (for example {@code education.student}).
     */
    @Column(name = "target_module_alias", type = ColumnType.VARCHAR, length = 128, comment = "Target platform module alias")
    private String targetModuleAlias;

    @Column(name = "target_metadata_id", type = ColumnType.VARCHAR, length = 32, comment = "Target metadata id")
    private String targetMetadataId;

    /**
     * Static targets do not have dynamic metadata.  Together with the complete static platform
     * module alias, this declares the entity segment of the registered {@code ReferenceTarget}.
     */
    @Column(name = "target_entity_alias", type = ColumnType.VARCHAR, length = 64, comment = "Static target entity alias")
    private String targetEntityAlias;

    @Column(name = "cardinality", type = ColumnType.VARCHAR, length = 16, nullable = false,
            comment = "Reference cardinality", defaultVal = @Default(varchar = "ONE"))
    private ReferenceCardinality cardinality = ReferenceCardinality.ONE;

    @Column(name = "target_unavailable_policy", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Reference target unavailable policy",
            defaultVal = @Default(varchar = "PRESERVE_HISTORY"))
    private ReferenceTargetUnavailablePolicy targetUnavailablePolicy = ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY;

    @Column(name = "projection_mappings", type = ColumnType.VARCHAR, length = 512, comment = "Projection mappings")
    private String projectionMappings;

    public List<ReferenceProjection> projections() {
        if (projectionMappings == null || projectionMappings.isBlank()) {
            return List.of();
        }
        return Arrays.stream(projectionMappings.split("[,;]"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(MetadataFieldReferenceConfig::projection)
                .toList();
    }

    public boolean targetsStaticEntity() {
        return targetEntityAlias != null && !targetEntityAlias.isBlank();
    }

    private static ReferenceProjection projection(String value) {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("reference projection mapping must use 'targetField:outputField': " + value);
        }
        return new ReferenceProjection(parts[0].trim(), parts[1].trim());
    }
}
