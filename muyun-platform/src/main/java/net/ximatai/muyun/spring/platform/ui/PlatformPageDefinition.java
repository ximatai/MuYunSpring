package net.ximatai.muyun.spring.platform.ui;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;

/**
 * Stable business page identity. Client, scope, template and composition are deliberately owned by
 * {@link PlatformPresentationVariant}, not by this definition.
 */
@Getter
@Setter
@Table(name = "platform_page_definition", comment = "Platform page definition")
@CompositeIndex(columns = {"module_alias", "alias"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "moduleAlias")
public class PlatformPageDefinition extends StandardEnabledSortableEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Module alias")
    private String moduleAlias;

    @Column(name = "alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Stable page alias within module")
    private String alias;

    @Column(name = "contract_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Page business contract type")
    @OptionField(type = OptionSourceType.ENUM, enumType = PlatformPageContractType.class)
    private PlatformPageContractType contractType;

    @Column(name = "main_relation_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Main module metadata relation anchor")
    private String mainRelationId;
}
