package net.ximatai.muyun.spring.platform.metadata;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.capability.PlatformManagedCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

@Getter
@Setter
@Table(name = "platform_metadata_field", comment = "Platform metadata field")
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "metadataId")
public class MetadataField extends StandardEnabledSortableEntity implements PlatformManagedCapable {
    @Column(name = "content_policy", type = ColumnType.VARCHAR, length = 32, comment = "Business content language policy")
    private net.ximatai.muyun.spring.common.content.ContentPolicy contentPolicy = net.ximatai.muyun.spring.common.content.ContentPolicy.KEEP_ORIGINAL;

    @Column(name = "metadata_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Metadata id")
    private String metadataId;

    @Column(name = "field_name", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Field name")
    private String fieldName;

    @Column(name = "column_name", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Column name")
    private String columnName;

    @Column(name = "field_spec_alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Field spec alias")
    private String fieldSpecAlias;

    @Column(name = "field_ownership", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Field ownership")
    private MetadataFieldOwnership fieldOwnership = MetadataFieldOwnership.BUSINESS;

    @Column(name = "field_form", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Field form")
    private MetadataFieldForm fieldForm = MetadataFieldForm.PHYSICAL;

    @Column(name = "owner_field_id", type = ColumnType.VARCHAR, length = 32, comment = "Owner metadata field id")
    private String ownerFieldId;

    @Column(name = "field_role", type = ColumnType.VARCHAR, length = 32, comment = "Field role")
    private MetadataFieldRole fieldRole;

    @Column(name = "system_managed", type = ColumnType.BOOLEAN, comment = "System managed flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean systemManaged = Boolean.FALSE;

    @Column(name = "required", type = ColumnType.BOOLEAN, comment = "Required flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean required = Boolean.FALSE;

    @Column(name = "unique_field", type = ColumnType.BOOLEAN, comment = "Unique field flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean uniqueField = Boolean.FALSE;

    @Column(name = "indexed", type = ColumnType.BOOLEAN, comment = "Indexed flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean indexed = Boolean.FALSE;

    @Column(name = "sortable_field", type = ColumnType.BOOLEAN, comment = "Sortable field flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean sortableField = Boolean.FALSE;

    @Column(name = "title_field", type = ColumnType.BOOLEAN, comment = "Title field flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean titleField = Boolean.FALSE;

}
