package net.ximatai.muyun.spring.platform.metadata;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardSortableEntity;

@Getter
@Setter
@Table(name = "platform_module_metadata_relation", comment = "Module metadata relation")
public class ModuleMetadataRelation extends StandardSortableEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Module alias")
    private String moduleAlias;

    @Column(name = "metadata_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Metadata id")
    private String metadataId;

    @Column(name = "relation_role", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Relation role",
            defaultVal = @Default(varchar = "main"))
    private RelationRole relationRole = RelationRole.MAIN;

    @Column(name = "parent_metadata_id", type = ColumnType.VARCHAR, length = 32, comment = "Parent metadata id")
    private String parentMetadataId;

    @Column(name = "foreign_key", type = ColumnType.VARCHAR, length = 64, comment = "Child foreign key field")
    private String foreignKey;

    @Column(name = "relation_alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Relation alias")
    private String relationAlias;

    @Column(name = "auto_populate", type = ColumnType.BOOLEAN, comment = "Auto populate child records",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean autoPopulate = Boolean.FALSE;

}
