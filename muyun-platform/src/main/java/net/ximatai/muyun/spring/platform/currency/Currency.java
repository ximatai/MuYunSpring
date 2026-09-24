package net.ximatai.muyun.spring.platform.currency;

import lombok.Getter;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;

import java.math.RoundingMode;

@Getter
@Setter
@Table(name = "platform_currency", comment = "Platform currency")
@TenantUniqueConstraint(fields = "code")
@SortPartitionBy(fields = {"tenantId"},
        message = "Currency sort can only move records within the same tenant scope")
public class Currency extends StandardEnabledSortableEntity {
    @Column(name = "code", type = ColumnType.VARCHAR, length = 3, nullable = false,
            comment = "ISO 4217 currency code")
    private String code;

    @Column(name = "numeric_code", type = ColumnType.VARCHAR, length = 3,
            comment = "ISO 4217 numeric code")
    private String numericCode;

    @Column(name = "symbol", type = ColumnType.VARCHAR, length = 16, comment = "Currency symbol")
    private String symbol;

    @Column(name = "decimal_scale", type = ColumnType.INT, nullable = false,
            comment = "Default decimal scale")
    private Integer decimalScale = 2;

    @Column(name = "rounding_mode", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Default rounding mode", defaultVal = @Default(varchar = "HALF_UP"))
    private RoundingMode roundingMode = RoundingMode.HALF_UP;
}
