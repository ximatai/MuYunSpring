package net.ximatai.muyun.spring.platform.metadata;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.ability.SortPartitionBy;

import java.util.Set;

@Getter
@Setter
@Table(name = "platform_metadata", comment = "Platform metadata")
@SortPartitionBy(fields = "applicationAlias")
public class Metadata extends StandardEnabledSortableEntity {
    @Column(name = "application_alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Application alias")
    private String applicationAlias;

    @Column(name = "alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Metadata alias")
    private String alias;

    @Column(name = "schema_name", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Physical schema name")
    private String schemaName;

    @Column(name = "table_name", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Physical table name")
    private String tableName;

    @Column(name = "data_scope_enabled", type = ColumnType.BOOLEAN, comment = "Data scope enabled",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean dataScopeEnabled = Boolean.FALSE;

    @Column(name = "sort_partition_fields", type = ColumnType.JSON_SET, comment = "Sort partition fields")
    private Set<String> sortPartitionFields;

    /**
     * Explicit dynamic capability declarations. A null value deliberately means a legacy metadata
     * record whose capabilities are still inferred from its saved fields.
     */
    @Column(name = "capability_declarations", type = ColumnType.JSON_SET,
            comment = "Explicit dynamic capability declarations")
    private Set<String> capabilityDeclarations;
}
