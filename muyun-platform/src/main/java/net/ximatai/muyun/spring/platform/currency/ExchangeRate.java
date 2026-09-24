package net.ximatai.muyun.spring.platform.currency;

import lombok.Getter;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Table(name = "platform_exchange_rate", comment = "Platform exchange rate")
@TenantUniqueConstraint(fields = {"fromCurrencyCode", "toCurrencyCode", "rateTypeCode", "effectiveDate"})
@SortPartitionBy(fields = {"tenantId", "fromCurrencyCode", "toCurrencyCode", "rateTypeCode"},
        message = "Exchange rate sort can only move records within the same currency pair and rate type")
public class ExchangeRate extends StandardEnabledSortableEntity {
    @Column(name = "from_currency_code", type = ColumnType.VARCHAR, length = 3, nullable = false,
            comment = "Source currency code")
    private String fromCurrencyCode;

    @Column(name = "to_currency_code", type = ColumnType.VARCHAR, length = 3, nullable = false,
            comment = "Target currency code")
    private String toCurrencyCode;

    @Column(name = "rate_type_code", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Exchange rate type code")
    private String rateTypeCode;

    @Column(name = "effective_date", type = ColumnType.DATE, nullable = false,
            comment = "Effective date")
    private LocalDate effectiveDate;

    @Column(name = "rate", type = ColumnType.NUMERIC, precision = 24, scale = 12, nullable = false,
            comment = "Exchange rate")
    private BigDecimal rate;

    @Column(name = "source", type = ColumnType.VARCHAR, length = 64, comment = "Rate source")
    private String source;
}
