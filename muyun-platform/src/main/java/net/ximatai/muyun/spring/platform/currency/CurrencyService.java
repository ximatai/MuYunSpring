package net.ximatai.muyun.spring.platform.currency;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class CurrencyService extends AbstractAbilityService<Currency> implements
        SoftDeleteAbility<Currency>,
        EnableAbility<Currency>,
        SortAbility<Currency>,
        ReferenceAbility<Currency>,
        CacheAbility<Currency>,
        QueryAbility<Currency> {
    public static final String MODULE_ALIAS = "platform.currency";

    public CurrencyService(BaseDao<Currency, String> currencyDao) {
        super(MODULE_ALIAS, Currency.class, currencyDao);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, Currency.class, java.util.List.of("id", "code", "numericCode", "symbol", "decimalScale", "roundingMode", "tenantId", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("code"));
    }

    @Override
    public void beforeInsert(Currency currency) {
        normalizeAndValidate(currency);
    }

    @Override
    public void beforeUpdate(Currency currency) {
        normalizeAndValidate(currency);
        validateImmutableIdentity(currency);
    }

    @Override
    public net.ximatai.muyun.spring.ability.SortPartition<Currency> sortPartition() {
        return net.ximatai.muyun.spring.ability.SortPartitions.of(
                currency -> Criteria.of().eqNullable(StandardEntitySchema.TENANT_ID_FIELD, currency.getTenantId()),
                net.ximatai.muyun.spring.ability.SortPartitions.byFieldsWithMessage(
                        "Currency sort can only move records within the same tenant scope", "tenantId"));
    }

    @Override
    public List<String> sortPartitionFields() {
        return List.of("tenantId");
    }

    public Currency resolveCurrency(String currencyCode) {
        String code = requireCurrencyCode(currencyCode);
        for (Currency currency : visibleCurrencyCandidates(code, false)) {
            return currency;
        }
        return null;
    }

    public Currency requireCurrency(String currencyCode) {
        Currency currency = resolveCurrency(currencyCode);
        if (currency == null) {
            throw new PlatformException("Currency requires existing visible currency: " + currencyCode);
        }
        return currency;
    }

    public Currency requireEnabledCurrency(String currencyCode) {
        Currency currency = requireCurrency(currencyCode);
        if (!Boolean.TRUE.equals(currency.getEnabled())) {
            throw new PlatformException("Currency is disabled: " + currencyCode);
        }
        return currency;
    }

    public List<Currency> listVisibleCurrencies(boolean enabledOnly) {
        Map<String, Currency> currencies = new LinkedHashMap<>();
        if (TenantContext.currentTenantId().isPresent()) {
            listTenantLayer(false).forEach(currency -> currencies.putIfAbsent(currency.getCode(), currency));
            listGlobalLayer(false).forEach(currency -> currencies.putIfAbsent(currency.getCode(), currency));
        } else {
            listGlobalLayer(false).forEach(currency -> currencies.putIfAbsent(currency.getCode(), currency));
        }
        return currencies.values().stream()
                .filter(currency -> !enabledOnly || Boolean.TRUE.equals(currency.getEnabled()))
                .toList();
    }

    private List<Currency> visibleCurrencyCandidates(String currencyCode, boolean enabledOnly) {
        return listVisibleCurrencies(enabledOnly).stream()
                .filter(currency -> Objects.equals(currency.getCode(), currencyCode))
                .toList();
    }

    private List<Currency> listTenantLayer(boolean enabledOnly) {
        Criteria criteria = Criteria.of();
        if (enabledOnly) {
            criteria.eq("enabled", Boolean.TRUE);
        }
        return list(criteria, new PageRequest(0, Integer.MAX_VALUE), Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    private List<Currency> listGlobalLayer(boolean enabledOnly) {
        try (TenantContext.Scope ignored = TenantContext.system("select global currencies")) {
            Criteria criteria = Criteria.of();
            if (enabledOnly) {
                criteria.eq("enabled", Boolean.TRUE);
            }
            return list(criteria, new PageRequest(0, Integer.MAX_VALUE), Sort.asc(PlatformAbilityFields.SORT_FIELD))
                    .stream()
                    .filter(currency -> currency.getTenantId() == null || currency.getTenantId().isBlank())
                    .toList();
        }
    }

    private void normalizeAndValidate(Currency currency) {
        currency.setCode(requireCurrencyCode(currency.getCode()));
        if (currency.getNumericCode() != null && !currency.getNumericCode().isBlank()) {
            currency.setNumericCode(requireNumericCode(currency.getNumericCode()));
        } else {
            currency.setNumericCode(null);
        }
        if (currency.getSymbol() != null && currency.getSymbol().isBlank()) {
            currency.setSymbol(null);
        }
        if (currency.getDecimalScale() == null) {
            currency.setDecimalScale(2);
        }
        if (currency.getDecimalScale() < 0) {
            throw new PlatformException("currency decimalScale must not be negative: " + currency.getCode());
        }
        if (currency.getRoundingMode() == null) {
            currency.setRoundingMode(RoundingMode.HALF_UP);
        }
        rejectDuplicate(currency, Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, currency.getTenantId())
                        .eq("code", currency.getCode()),
                "currency code must be unique within tenant scope: " + currency.getCode());
    }

    private void validateImmutableIdentity(Currency currency) {
        Currency existing = selectIncludingDeleted(currency.getId());
        rejectChanged(existing, currency, "Currency code", Currency::getCode);
        rejectChanged(existing, currency, "Currency numeric code", Currency::getNumericCode);
    }

    private String requireCurrencyCode(String value) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("currencyCode must not be blank");
        }
        String code = CurrencyCodeRules.normalizeCurrencyCode(value);
        if (!CurrencyCodeRules.isCurrencyCode(code)) {
            throw new PlatformException("currencyCode must be ISO 4217 alpha-3 code: " + value);
        }
        return code;
    }

    private String requireNumericCode(String value) {
        String text = value.trim();
        if (!text.matches("\\d{3}")) {
            throw new PlatformException("currency numericCode must be 3 digits: " + value);
        }
        return text;
    }
}
