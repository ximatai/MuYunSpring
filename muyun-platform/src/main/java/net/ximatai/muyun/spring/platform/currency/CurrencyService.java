package net.ximatai.muyun.spring.platform.currency;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.TenantLayerAbility;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class CurrencyService extends StandardBusinessService<Currency> implements
        TenantLayerAbility<Currency>,
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
    public void beforeUpdate(Currency currency, Currency existing) {
        super.beforeUpdate(currency);
        validateImmutableIdentity(currency, existing);
    }

    public Currency resolveCurrency(String code) {
        return listTenantAndGlobal(Criteria.of().eq("code", requireCurrencyCode(code)),
                Sort.asc(PlatformAbilityFields.SORT_FIELD)).stream().findFirst().orElse(null);
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
        Map<String, Currency> records = new LinkedHashMap<>();
        listTenantAndGlobal(Criteria.of(), Sort.asc(PlatformAbilityFields.SORT_FIELD))
                .forEach(record -> records.putIfAbsent(record.getCode(), record));
        // A disabled tenant override still masks the global definition.
        return records.values().stream()
                .filter(record -> !enabledOnly || Boolean.TRUE.equals(record.getEnabled()))
                .toList();
    }

    @Override
    protected void validateBeforeSave(Currency currency) {
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
    }

    private void validateImmutableIdentity(Currency currency, Currency existing) {
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
