package net.ximatai.muyun.spring.platform.measure;

import lombok.Getter;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Table(name = "platform_measure_unit_conversion_rule", comment = "Platform measure unit conversion rule")
@CompositeIndex(columns = {
        "tenant_id", "application_alias", "scope_type", "module_alias", "context_object_type", "context_object_id",
        "from_category_alias", "from_unit_code", "to_category_alias", "to_unit_code"
})
@SortPartitionBy(fields = {"tenantId", "applicationAlias", "scopeType", "moduleAlias", "contextObjectType",
        "contextObjectId"},
        message = "Measure unit conversion rule sort can only move records within the same tenant and scope")
public class MeasureUnitConversionRule extends StandardEnabledSortableEntity {
    @Column(name = "application_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Application alias")
    private String applicationAlias;

    @Column(name = "scope_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Conversion scope type", defaultVal = @Default(varchar = "global"))
    private MeasureUnitConversionScopeType scopeType = MeasureUnitConversionScopeType.GLOBAL;

    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, comment = "Module alias")
    private String moduleAlias;

    @Column(name = "context_object_type", type = ColumnType.VARCHAR, length = 64, comment = "Context object type")
    private String contextObjectType;

    @Column(name = "context_object_id", type = ColumnType.VARCHAR, length = 64, comment = "Context object id")
    private String contextObjectId;

    @Column(name = "from_category_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Source unit category alias")
    private String fromCategoryAlias;

    @Column(name = "from_unit_code", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Source unit code")
    private String fromUnitCode;

    @Column(name = "to_category_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Target unit category alias")
    private String toCategoryAlias;

    @Column(name = "to_unit_code", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Target unit code")
    private String toUnitCode;

    @Column(name = "factor", type = ColumnType.NUMERIC, precision = 24, scale = 12, nullable = false,
            comment = "Conversion factor")
    private BigDecimal factor = BigDecimal.ONE;

    @Column(name = "priority", type = ColumnType.INT, comment = "Rule priority")
    private Integer priority;

    @Column(name = "effective_from", type = ColumnType.TIMESTAMP, comment = "Effective from")
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to", type = ColumnType.TIMESTAMP, comment = "Effective to")
    private LocalDateTime effectiveTo;
}
