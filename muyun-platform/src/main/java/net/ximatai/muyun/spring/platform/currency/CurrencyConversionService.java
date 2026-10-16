package net.ximatai.muyun.spring.platform.currency;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
public class CurrencyConversionService {
    private final CurrencyService currencyService;
    private final ExchangeRateService exchangeRateService;

    public CurrencyConversionService(CurrencyService currencyService,
                                     ExchangeRateService exchangeRateService) {
        this.currencyService = currencyService;
        this.exchangeRateService = exchangeRateService;
    }

    public CurrencyConversion convert(BigDecimal amount,
                                      String fromCurrencyCode,
                                      String toCurrencyCode,
                                      String rateTypeCode,
                                      LocalDate rateDate) {
        if (amount == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.currency-conversion.currency-conversion-amount-must-not-be-null",
                    "currency conversion amount must not be null"));
        }
        Currency fromCurrency = currencyService.requireEnabledCurrency(fromCurrencyCode);
        Currency toCurrency = currencyService.requireEnabledCurrency(toCurrencyCode);
        LocalDate validRateDate = rateDate == null ? LocalDate.now() : rateDate;
        if (fromCurrency.getCode().equals(toCurrency.getCode())) {
            String validRateTypeCode = requireEnabledRateTypeCode(rateTypeCode);
            return new CurrencyConversion(fromCurrency.getCode(), toCurrency.getCode(), validRateTypeCode,
                    validRateDate, amount, BigDecimal.ONE, round(amount, toCurrency));
        }
        ExchangeRate rate = exchangeRateService.requireEffectiveRate(
                fromCurrency.getCode(), toCurrency.getCode(), rateTypeCode, validRateDate);
        BigDecimal converted = round(amount.multiply(rate.getRate()), toCurrency);
        return new CurrencyConversion(fromCurrency.getCode(), toCurrency.getCode(), rate.getRateTypeCode(),
                validRateDate, amount, rate.getRate(), converted);
    }

    private BigDecimal round(BigDecimal amount, Currency currency) {
        Integer scale = currency.getDecimalScale();
        RoundingMode roundingMode = currency.getRoundingMode() == null ? RoundingMode.HALF_UP : currency.getRoundingMode();
        return scale == null ? amount : amount.setScale(scale, roundingMode);
    }

    private String requireEnabledRateTypeCode(String rateTypeCode) {
        return exchangeRateService.requireEnabledRateTypeCode(rateTypeCode);
    }
}
