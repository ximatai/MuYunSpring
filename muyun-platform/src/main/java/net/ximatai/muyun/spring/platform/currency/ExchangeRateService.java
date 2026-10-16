package net.ximatai.muyun.spring.platform.currency;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.TenantLayerAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class ExchangeRateService extends StandardBusinessService<ExchangeRate> implements
        TenantLayerAbility<ExchangeRate>,
        SoftDeleteAbility<ExchangeRate>,
        EnableAbility<ExchangeRate>,
        SortAbility<ExchangeRate>,
        ReferenceAbility<ExchangeRate>,
        QueryAbility<ExchangeRate> {
    public static final String MODULE_ALIAS = "platform.exchange_rate";

    private final CurrencyService currencyService;
    private final ExchangeRateTypeService rateTypeService;

    public ExchangeRateService(BaseDao<ExchangeRate, String> exchangeRateDao,
                               CurrencyService currencyService,
                               ExchangeRateTypeService rateTypeService) {
        super(MODULE_ALIAS, ExchangeRate.class, exchangeRateDao);
        this.currencyService = currencyService;
        this.rateTypeService = rateTypeService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, ExchangeRate.class, java.util.List.of("id", "fromCurrencyCode", "toCurrencyCode", "rateTypeCode", "effectiveDate", "rate", "source", "tenantId", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.desc("effectiveDate"),
                net.ximatai.muyun.database.core.orm.Sort.asc("fromCurrencyCode"),
                net.ximatai.muyun.database.core.orm.Sort.asc("toCurrencyCode"));
    }

    @Override
    protected void validateBeforeUpdate(ExchangeRate rate, ExchangeRate existing) {
        validateImmutableIdentity(rate, existing);
    }

    public ExchangeRate resolveEffectiveRate(String fromCurrencyCode,
                                             String toCurrencyCode,
                                             String rateTypeCode,
                                             LocalDate rateDate) {
        String from = requireCurrencyCode(fromCurrencyCode);
        String to = requireCurrencyCode(toCurrencyCode);
        String typeCode = rateTypeService.requireEnabledRateType(rateTypeCode).getCode();
        LocalDate date = requireRateDate(rateDate);
        if (TenantContext.currentTenantId().isEmpty()) {
            return effectiveRate(listGlobalRateLayer(from, to, typeCode), date);
        }
        ExchangeRate tenantRate = effectiveRate(listRateLayer(from, to, typeCode), date);
        if (tenantRate != null) {
            return tenantRate;
        }
        return effectiveRate(listGlobalRateLayer(from, to, typeCode), date);
    }

    public ExchangeRate requireEffectiveRate(String fromCurrencyCode,
                                             String toCurrencyCode,
                                             String rateTypeCode,
                                             LocalDate rateDate) {
        ExchangeRate rate = resolveEffectiveRate(fromCurrencyCode, toCurrencyCode, rateTypeCode, rateDate);
        if (rate == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.exchange-rate.exchange-rate-requires-effective-rate",
                    "Exchange rate requires effective rate: {value1} -> {value2} {value3} {value4}"), Map.of("value1", String.valueOf(fromCurrencyCode), "value2", String.valueOf(toCurrencyCode), "value3", String.valueOf(rateTypeCode), "value4", String.valueOf(rateDate)));
        }
        return rate;
    }

    public String requireEnabledRateTypeCode(String rateTypeCode) {
        return rateTypeService.requireEnabledRateType(rateTypeCode).getCode();
    }

    @Override
    protected void validateBeforeSave(ExchangeRate rate) {
        Currency from = currencyService.requireEnabledCurrency(rate.getFromCurrencyCode());
        Currency to = currencyService.requireEnabledCurrency(rate.getToCurrencyCode());
        if (from.getCode().equals(to.getCode())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.exchange-rate.exchange-rate-source-and-target-currency-must-be-different",
                    "exchange rate source and target currency must be different: {value1}"), Map.of("value1", String.valueOf(from.getCode())));
        }
        ExchangeRateType rateType = rateTypeService.requireEnabledRateType(rate.getRateTypeCode());
        rate.setFromCurrencyCode(from.getCode());
        rate.setToCurrencyCode(to.getCode());
        rate.setRateTypeCode(rateType.getCode());
        rate.setEffectiveDate(requireRateDate(rate.getEffectiveDate()));
        if (rate.getRate() == null || rate.getRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.exchange-rate.exchange-rate-value-must-be-positive",
                    "exchange rate value must be positive"));
        }
        if (rate.getSource() != null && rate.getSource().isBlank()) {
            rate.setSource(null);
        }
    }

    private void validateImmutableIdentity(ExchangeRate rate, ExchangeRate existing) {
        rejectChangedText(existing, rate, TextReference.code(
                "platform.rejection.exchange-rate.exchange-rate-source-currency-cannot-be-changed",
                "Exchange rate source currency cannot be changed"), ExchangeRate::getFromCurrencyCode);
        rejectChangedText(existing, rate, TextReference.code(
                "platform.rejection.exchange-rate.exchange-rate-target-currency-cannot-be-changed",
                "Exchange rate target currency cannot be changed"), ExchangeRate::getToCurrencyCode);
        rejectChangedText(existing, rate, TextReference.code(
                "platform.rejection.exchange-rate.exchange-rate-type-cannot-be-changed",
                "Exchange rate type cannot be changed"), ExchangeRate::getRateTypeCode);
        rejectChangedText(existing, rate, TextReference.code(
                "platform.rejection.exchange-rate.exchange-rate-effective-date-cannot-be-changed",
                "Exchange rate effective date cannot be changed"), ExchangeRate::getEffectiveDate);
    }

    private ExchangeRate effectiveRate(List<ExchangeRate> rates, LocalDate rateDate) {
        return rates.stream()
                .filter(rate -> !rate.getEffectiveDate().isAfter(rateDate))
                .findFirst()
                .orElse(null);
    }

    private List<ExchangeRate> listRateLayer(String fromCurrencyCode,
                                             String toCurrencyCode,
                                             String rateTypeCode) {
        return listCurrentTenant(rateCriteria(fromCurrencyCode, toCurrencyCode, rateTypeCode), Sort.desc("effectiveDate"));
    }

    private List<ExchangeRate> listGlobalRateLayer(String fromCurrencyCode,
                                                   String toCurrencyCode,
                                                   String rateTypeCode) {
        return listGlobal(rateCriteria(fromCurrencyCode, toCurrencyCode, rateTypeCode), Sort.desc("effectiveDate"));
    }

    private Criteria rateCriteria(String fromCurrencyCode, String toCurrencyCode, String rateTypeCode) {
        return Criteria.of()
                .eq("fromCurrencyCode", fromCurrencyCode)
                .eq("toCurrencyCode", toCurrencyCode)
                .eq("rateTypeCode", rateTypeCode)
                .eq("enabled", Boolean.TRUE);
    }

    private String requireCurrencyCode(String value) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.exchange-rate.currencycode-must-not-be-blank",
                    "currencyCode must not be blank"));
        }
        String code = value.trim().toUpperCase();
        if (!code.matches("[A-Z]{3}")) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.exchange-rate.currencycode-must-be-iso-4217-alpha-3-code",
                    "currencyCode must be ISO 4217 alpha-3 code: {value1}"), Map.of("value1", String.valueOf(value)));
        }
        return code;
    }

    private LocalDate requireRateDate(LocalDate rateDate) {
        if (rateDate == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.exchange-rate.exchange-rate-effectivedate-must-not-be-null",
                    "exchange rate effectiveDate must not be null"));
        }
        return rateDate;
    }
}
