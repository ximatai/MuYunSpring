package net.ximatai.muyun.spring.platform.currency;

import lombok.Getter;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.capability.PlatformManagedCapable;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

@Getter
@Setter
@Table(name = "platform_exchange_rate_type", comment = "Platform exchange rate type")
@TenantUniqueConstraint(fields = "code")
@SortPartitionBy(fields = {"tenantId"},
        message = "Exchange rate type sort can only move records within the same tenant scope")
public class ExchangeRateType extends StandardEnabledSortableEntity implements PlatformManagedCapable {
    @Column(name = "code", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Exchange rate type code")
    private String code;

    @Column(name = "system_managed", type = ColumnType.BOOLEAN, comment = "System managed flag")
    private Boolean systemManaged = Boolean.FALSE;
}
