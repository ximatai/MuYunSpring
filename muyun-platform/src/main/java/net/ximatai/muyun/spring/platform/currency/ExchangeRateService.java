package net.ximatai.muyun.spring.platform.currency;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class ExchangeRateService extends AbstractAbilityService<ExchangeRate> implements
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
    public void beforeInsert(ExchangeRate rate) {
        normalizeAndValidate(rate);
    }

    @Override
    public void beforeUpdate(ExchangeRate rate) {
        normalizeAndValidate(rate);
        validateImmutableIdentity(rate);
    }

    @Override
    public net.ximatai.muyun.spring.ability.SortPartition<ExchangeRate> sortPartition() {
        return net.ximatai.muyun.spring.ability.SortPartitions.of(rate -> Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, rate.getTenantId())
                        .eq("fromCurrencyCode", rate.getFromCurrencyCode())
                        .eq("toCurrencyCode", rate.getToCurrencyCode())
                        .eq("rateTypeCode", rate.getRateTypeCode()),
                net.ximatai.muyun.spring.ability.SortPartitions.byFieldsWithMessage(
                "Exchange rate sort can only move records within the same currency pair and rate type",
                "tenantId", "fromCurrencyCode", "toCurrencyCode", "rateTypeCode"));
    }

    @Override
    public List<String> sortPartitionFields() {
        return List.of("tenantId", "fromCurrencyCode", "toCurrencyCode", "rateTypeCode");
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
            throw new PlatformException("Exchange rate requires effective rate: "
                    + fromCurrencyCode + " -> " + toCurrencyCode + " " + rateTypeCode + " " + rateDate);
        }
        return rate;
    }

    public String requireEnabledRateTypeCode(String rateTypeCode) {
        return rateTypeService.requireEnabledRateType(rateTypeCode).getCode();
    }

    private void normalizeAndValidate(ExchangeRate rate) {
        Currency from = currencyService.requireEnabledCurrency(rate.getFromCurrencyCode());
        Currency to = currencyService.requireEnabledCurrency(rate.getToCurrencyCode());
        if (from.getCode().equals(to.getCode())) {
            throw new PlatformException("exchange rate source and target currency must be different: " + from.getCode());
        }
        ExchangeRateType rateType = rateTypeService.requireEnabledRateType(rate.getRateTypeCode());
        rate.setFromCurrencyCode(from.getCode());
        rate.setToCurrencyCode(to.getCode());
        rate.setRateTypeCode(rateType.getCode());
        rate.setEffectiveDate(requireRateDate(rate.getEffectiveDate()));
        if (rate.getRate() == null || rate.getRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PlatformException("exchange rate value must be positive");
        }
        if (rate.getSource() != null && rate.getSource().isBlank()) {
            rate.setSource(null);
        }
        rejectDuplicate(rate, Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, rate.getTenantId())
                        .eq("fromCurrencyCode", rate.getFromCurrencyCode())
                        .eq("toCurrencyCode", rate.getToCurrencyCode())
                        .eq("rateTypeCode", rate.getRateTypeCode())
                        .eq("effectiveDate", rate.getEffectiveDate()),
                "exchange rate must be unique within tenant, currency pair, rate type and effective date");
    }

    private void validateImmutableIdentity(ExchangeRate rate) {
        ExchangeRate existing = selectIncludingDeleted(rate.getId());
        rejectChanged(existing, rate, "Exchange rate source currency", ExchangeRate::getFromCurrencyCode);
        rejectChanged(existing, rate, "Exchange rate target currency", ExchangeRate::getToCurrencyCode);
        rejectChanged(existing, rate, "Exchange rate type", ExchangeRate::getRateTypeCode);
        rejectChanged(existing, rate, "Exchange rate effective date", ExchangeRate::getEffectiveDate);
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
        return list(rateCriteria(fromCurrencyCode, toCurrencyCode, rateTypeCode),
                new PageRequest(0, Integer.MAX_VALUE), Sort.desc("effectiveDate"));
    }

    private List<ExchangeRate> listGlobalRateLayer(String fromCurrencyCode,
                                                   String toCurrencyCode,
                                                   String rateTypeCode) {
        try (TenantContext.Scope ignored = TenantContext.system("select global exchange rates")) {
            return list(rateCriteria(fromCurrencyCode, toCurrencyCode, rateTypeCode),
                    new PageRequest(0, Integer.MAX_VALUE), Sort.desc("effectiveDate"))
                    .stream()
                    .filter(rate -> rate.getTenantId() == null || rate.getTenantId().isBlank())
                    .toList();
        }
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
            throw new PlatformException("currencyCode must not be blank");
        }
        String code = value.trim().toUpperCase();
        if (!code.matches("[A-Z]{3}")) {
            throw new PlatformException("currencyCode must be ISO 4217 alpha-3 code: " + value);
        }
        return code;
    }

    private LocalDate requireRateDate(LocalDate rateDate) {
        if (rateDate == null) {
            throw new PlatformException("exchange rate effectiveDate must not be null");
        }
        return rateDate;
    }
}
