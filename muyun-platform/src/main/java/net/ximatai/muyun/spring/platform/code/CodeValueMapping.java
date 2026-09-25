package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

@Getter
@Setter
@Table(name = "platform_code_value_mapping", comment = "Platform code value mapping")
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "segmentId")
public class CodeValueMapping extends StandardEnabledSortableEntity {
    @Indexed
    @Column(name = "segment_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Code segment id")
    @Required
    private String segmentId;

    @Indexed
    @Column(name = "source_value", type = ColumnType.VARCHAR, length = 128, comment = "Source value")
    private String sourceValue;

    @Column(name = "target_value", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Target value")
    private String targetValue;

    @Column(name = "default_mapping", type = ColumnType.BOOLEAN, comment = "Whether this is the default mapping",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean defaultMapping = Boolean.FALSE;

    @Column(name = "org_scope_type", type = ColumnType.VARCHAR, length = 32, comment = "Organization scope type")
    private CodeOrgScopeType orgScopeType = CodeOrgScopeType.GLOBAL;

    @Indexed
    @Column(name = "org_scope_id", type = ColumnType.VARCHAR, length = 64, comment = "Organization scope id")
    private String orgScopeId;
}
