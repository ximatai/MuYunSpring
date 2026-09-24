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

import java.math.BigDecimal;
import java.math.RoundingMode;

@Getter
@Setter
@Table(name = "platform_measure_unit", comment = "Platform measure unit")
@TenantUniqueConstraint(fields = {"applicationAlias", "categoryAlias", "code"})
@SortPartitionBy(fields = {"tenantId", "applicationAlias", "categoryAlias"},
        message = "Measure unit sort can only move records within the same category")
public class MeasureUnit extends StandardEnabledSortableEntity {
    @Column(name = "application_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Application alias")
    private String applicationAlias;

    @Column(name = "category_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Measure unit category alias")
    private String categoryAlias;

    @Column(name = "code", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Measure unit code")
    private String code;

    @Column(name = "symbol", type = ColumnType.VARCHAR, length = 32, comment = "Measure unit symbol")
    private String symbol;

    @Column(name = "scale", type = ColumnType.INT, comment = "Default decimal scale")
    private Integer scale;

    @Column(name = "factor_to_base", type = ColumnType.NUMERIC, precision = 24, scale = 12, nullable = false,
            comment = "Factor to base unit")
    private BigDecimal factorToBase = BigDecimal.ONE;

    @Column(name = "offset_to_base", type = ColumnType.NUMERIC, precision = 24, scale = 12, nullable = false,
            comment = "Offset to base unit")
    private BigDecimal offsetToBase = BigDecimal.ZERO;

    @Column(name = "rounding_mode", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Default rounding mode", defaultVal = @Default(varchar = "HALF_UP"))
    private RoundingMode roundingMode = RoundingMode.HALF_UP;
}
