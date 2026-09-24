package net.ximatai.muyun.spring.platform.measure;

import lombok.Getter;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;

@Getter
@Setter
@Table(name = "platform_measure_unit_category", comment = "Platform measure unit category")
@TenantUniqueConstraint(fields = {"applicationAlias", "alias"})
@SortPartitionBy(fields = {"tenantId", "applicationAlias"},
        message = "Measure unit category sort can only move records within the same tenant and application")
public class MeasureUnitCategory extends StandardEnabledSortableEntity {
    @Column(name = "application_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Application alias")
    private String applicationAlias;

    @Column(name = "alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Measure unit category alias")
    private String alias;

    @Column(name = "dimension", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Measure dimension", defaultVal = @Default(varchar = "custom"))
    private MeasureDimension dimension = MeasureDimension.CUSTOM;

    @Column(name = "base_unit_code", type = ColumnType.VARCHAR, length = 64,
            comment = "Base unit code")
    private String baseUnitCode;
}
