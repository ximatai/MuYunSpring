package net.ximatai.muyun.spring.platform.currency;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CurrencyServiceContractTest {
    private final TestMemoryDao<Currency> currencyDao = new TestMemoryDao<>();
    private final TestMemoryDao<ExchangeRateType> rateTypeDao = new TestMemoryDao<>();
    private final TestMemoryDao<ExchangeRate> rateDao = new TestMemoryDao<>();
    private final TestMemoryDao<TenantCurrencySetting> tenantSettingDao = new TestMemoryDao<>();
    private final CurrencyService currencyService = new CurrencyService(currencyDao);
    private final ExchangeRateTypeService rateTypeService = new ExchangeRateTypeService(rateTypeDao);
    private final ExchangeRateService rateService = new ExchangeRateService(rateDao, currencyService, rateTypeService);
    private final CurrencyConversionService conversionService = new CurrencyConversionService(currencyService, rateService);
    private final TenantCurrencySettingService tenantSettingService =
            new TenantCurrencySettingService(tenantSettingDao, currencyService);

    @Test
    void shouldCreateCurrencyWithIsoCodeAndScale() {
        String id = currencyService.insert(currency("cny", "156", "人民币", "¥", 2));

        Currency loaded = currencyService.select(id);
        assertThat(loaded.getCode()).isEqualTo("CNY");
        assertThat(loaded.getNumericCode()).isEqualTo("156");
        assertThat(loaded.getDecimalScale()).isEqualTo(2);
        assertThat(loaded.getRoundingMode()).isEqualTo(RoundingMode.HALF_UP);
    }

    @Test
    void shouldRejectInvalidCurrencyShapeAndDuplicateCode() {
        currencyService.insert(currency("USD", "840", "US Dollar", "$", 2));

        assertThatThrownBy(() -> currencyService.insert(currency("US", "840", "Bad", "$", 2)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("ISO 4217");
        assertThatThrownBy(() -> currencyService.insert(currency("EUR", "84", "Euro", "€", 2)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("3 digits");
        assertThatThrownBy(() -> currencyService.insert(currency("USD", "840", "Duplicate", "$", 2)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void updateUsesTheMutationSnapshotAndStillProtectsCurrencyIdentity() {
        var service = spy(new CurrencyService(currencyDao));
        Currency original = currency("USD", "840", "Dollar", "$", 2);
        service.insert(original);
        Currency update = currency(" usd ", "840", "Updated Dollar", "$", 2);
        update.setId(original.getId()); update.setVersion(original.getVersion());
        clearInvocations(service);
        assertThat(service.update(update)).isEqualTo(1);
        verify(service).selectActiveRaw(original.getId());
        verify(service, never()).selectIgnoreSoftDelete(original.getId());
        assertThat(update.getCode()).isEqualTo("USD");
        Currency changedIdentity = currency("EUR", "978", "Euro", "€", 2);
        changedIdentity.setId(update.getId()); changedIdentity.setVersion(update.getVersion());
        assertThatThrownBy(() -> service.update(changedIdentity)).hasMessageContaining("cannot be changed");
        assertThat(currencyDao.findById(original.getId()).getCode()).isEqualTo("USD");
    }

    @Test
    void shouldPreferTenantCurrencyOverGlobalCurrency() {
        currencyService.insert(currency("USD", "840", "Global Dollar", "$", 2));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            currencyService.insert(currency("USD", "840", "Tenant Dollar", "US$", 3));

            Currency visible = currencyService.requireCurrency("USD");

            assertThat(visible.getTitle()).isEqualTo("Tenant Dollar");
            assertThat(visible.getDecimalScale()).isEqualTo(3);
        }
    }

    @Test
    void shouldNotReadTenantCurrencyWithoutTenantContext() {
        currencyService.insert(currency("USD", "840", "Global Dollar", "$", 2));
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            currencyService.insert(currency("USD", "840", "Tenant Dollar", "US$", 3));
        }

        Currency visible = currencyService.requireCurrency("USD");

        assertThat(visible.getTitle()).isEqualTo("Global Dollar");
        assertThat(visible.getTenantId()).isNull();
    }

    @Test
    void shouldNotFallbackToGlobalCurrencyWhenTenantCurrencyIsDisabled() {
        currencyService.insert(currency("USD", "840", "Global Dollar", "$", 2));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String tenantCurrencyId = currencyService.insert(currency("USD", "840", "Tenant Dollar", "US$", 2));
            currencyService.disable(tenantCurrencyId);

            assertThatThrownBy(() -> currencyService.requireEnabledCurrency("USD"))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("disabled");
        }
    }

    @Test
    void shouldHideGlobalCurrencyOptionWhenTenantCurrencyIsDisabled() {
        currencyService.insert(currency("USD", "840", "Global Dollar", "$", 2));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String tenantCurrencyId = currencyService.insert(currency("USD", "840", "Tenant Dollar", "US$", 2));
            currencyService.disable(tenantCurrencyId);

            assertThat(currencyService.listVisibleCurrencies(true))
                    .extracting(Currency::getCode)
                    .doesNotContain("USD");
            assertThat(currencyService.listVisibleCurrencies(false))
                    .singleElement()
                    .satisfies(currency -> {
                        assertThat(currency.getCode()).isEqualTo("USD");
                        assertThat(currency.getTitle()).isEqualTo("Tenant Dollar");
                        assertThat(currency.getEnabled()).isFalse();
                    });
        }
    }

    @Test
    void shouldConfigureTenantBaseCurrencyFromVisibleCurrency() {
        currencyService.insert(currency("CNY", "156", "人民币", "¥", 2));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String id = tenantSettingService.insert(setting("CNY"));

            assertThat(tenantSettingService.select(id).getTenantId()).isEqualTo("tenant-a");
            assertThat(tenantSettingService.requireCurrentBaseCurrencyCode()).isEqualTo("CNY");
        }
    }

    @Test
    void shouldCreateRateTypeAndRequireEnabledRateType() {
        String id = rateTypeService.insert(rateType("spot", "Spot"));

        assertThat(rateTypeService.requireEnabledRateType("SPOT").getId()).isEqualTo(id);
        rateTypeService.disable(id);
        assertThatThrownBy(() -> rateTypeService.requireEnabledRateType("SPOT"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void shouldProtectSystemManagedExchangeRateTypeFromOrdinaryMutation() {
        ExchangeRateType managed = rateType("spot", "Spot");
        managed.setSystemManaged(Boolean.TRUE);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> rateTypeService.insert(managed));

        ExchangeRateType protectedUpdate = new ExchangeRateType();
        protectedUpdate.setId(managed.getId());
        protectedUpdate.setVersion(managed.getVersion());
        protectedUpdate.setCode("SPOT");
        protectedUpdate.setTitle("Changed");

        assertThatThrownBy(() -> rateTypeService.update(protectedUpdate))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
        assertThatThrownBy(() -> rateTypeService.delete(managed.getId()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
    }

    @Test
    void shouldAllowOrdinaryEnabledAndSortUpdateOnSystemManagedExchangeRateType() {
        ExchangeRateType managed = rateType("spot", "Spot");
        managed.setSystemManaged(Boolean.TRUE);
        managed.setSortOrder(10);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> rateTypeService.insert(managed));

        ExchangeRateType update = new ExchangeRateType();
        update.setId(managed.getId());
        update.setVersion(managed.getVersion());
        update.setEnabled(Boolean.FALSE);
        update.setSortOrder(20);

        assertThat(rateTypeService.update(update)).isEqualTo(1);

        ExchangeRateType selected = rateTypeService.select(managed.getId());
        assertThat(selected.getEnabled()).isFalse();
        assertThat(selected.getSortOrder()).isEqualTo(20);
        assertThat(selected.getCode()).isEqualTo("SPOT");
        assertThat(selected.getTitle()).isEqualTo("Spot");
        assertThat(selected.getSystemManaged()).isTrue();
    }

    @Test
    void shouldNotReadTenantRateTypeWithoutTenantContext() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            rateTypeService.insert(rateType("spot", "Tenant Spot"));
        }

        assertThatThrownBy(() -> rateTypeService.requireRateType("SPOT"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires existing");
    }

    @Test
    void shouldHideGlobalRateTypeOptionWhenTenantRateTypeIsDisabled() {
        rateTypeService.insert(rateType("spot", "Global Spot"));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String tenantRateTypeId = rateTypeService.insert(rateType("spot", "Tenant Spot"));
            rateTypeService.disable(tenantRateTypeId);

            assertThat(rateTypeService.listVisibleRateTypes(true))
                    .extracting(ExchangeRateType::getCode)
                    .doesNotContain("SPOT");
            assertThat(rateTypeService.listVisibleRateTypes(false))
                    .singleElement()
                    .satisfies(rateType -> {
                        assertThat(rateType.getCode()).isEqualTo("SPOT");
                        assertThat(rateType.getTitle()).isEqualTo("Tenant Spot");
                        assertThat(rateType.getEnabled()).isFalse();
                    });
        }
    }

    @Test
    void shouldResolveLatestEffectiveRateNotAfterRateDate() {
        prepareCurrenciesAndRateType();
        rateService.insert(rate("USD", "CNY", "SPOT", "2026-01-01", "7.1000"));
        rateService.insert(rate("USD", "CNY", "SPOT", "2026-02-01", "7.2000"));
        rateService.insert(rate("USD", "CNY", "SPOT", "2026-03-01", "7.3000"));

        ExchangeRate resolved = rateService.requireEffectiveRate("USD", "CNY", "SPOT",
                LocalDate.of(2026, 2, 15));

        assertThat(resolved.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(resolved.getRate()).isEqualByComparingTo("7.2000");
    }

    @Test
    void shouldConvertAmountWithTargetCurrencyScale() {
        prepareCurrenciesAndRateType();
        rateService.insert(rate("USD", "CNY", "SPOT", "2026-02-01", "7.2345"));

        CurrencyConversion conversion = conversionService.convert(new BigDecimal("12.345"),
                "USD", "CNY", "SPOT", LocalDate.of(2026, 2, 16));

        assertThat(conversion.exchangeRate()).isEqualByComparingTo("7.2345");
        assertThat(conversion.convertedAmount()).isEqualByComparingTo("89.31");
    }

    @Test
    void shouldUseGlobalRateWhenTenantRateIsMissingAndPreferTenantRateWhenPresent() {
        prepareCurrenciesAndRateType();
        rateService.insert(rate("USD", "CNY", "SPOT", "2026-01-01", "7.1000"));
        rateService.insert(rate("USD", "CNY", "SPOT", "2026-02-01", "7.9000"));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            CurrencyConversion globalFallback = conversionService.convert(BigDecimal.ONE,
                    "USD", "CNY", "SPOT", LocalDate.of(2026, 1, 10));
            assertThat(globalFallback.exchangeRate()).isEqualByComparingTo("7.1000");

            rateService.insert(rate("USD", "CNY", "SPOT", "2026-01-01", "7.2000"));
            CurrencyConversion tenantRate = conversionService.convert(BigDecimal.ONE,
                    "USD", "CNY", "SPOT", LocalDate.of(2026, 2, 10));
            assertThat(tenantRate.exchangeRate()).isEqualByComparingTo("7.2000");
        }
    }

    @Test
    void shouldNotReadTenantExchangeRateWithoutTenantContext() {
        prepareCurrenciesAndRateType();
        rateService.insert(rate("USD", "CNY", "SPOT", "2026-01-01", "7.1000"));
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            rateService.insert(rate("USD", "CNY", "SPOT", "2026-01-01", "8.0000"));
        }

        CurrencyConversion conversion = conversionService.convert(BigDecimal.ONE,
                "USD", "CNY", "SPOT", LocalDate.of(2026, 1, 10));

        assertThat(conversion.exchangeRate()).isEqualByComparingTo("7.1000");
    }

    @Test
    void shouldValidateRateTypeWhenConvertingSameCurrency() {
        prepareCurrenciesAndRateType();

        CurrencyConversion conversion = conversionService.convert(new BigDecimal("1.234"),
                "CNY", "CNY", "spot", LocalDate.of(2026, 1, 10));

        assertThat(conversion.rateTypeCode()).isEqualTo("SPOT");
        assertThat(conversion.exchangeRate()).isEqualByComparingTo("1");
        assertThat(conversion.convertedAmount()).isEqualByComparingTo("1.23");
        rateTypeService.disable(rateTypeService.requireRateType("SPOT").getId());
        assertThatThrownBy(() -> conversionService.convert(BigDecimal.ONE,
                "CNY", "CNY", "SPOT", LocalDate.of(2026, 1, 10)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void shouldNotValidateSameCurrencyConversionWithTenantOnlyRateTypeWithoutTenantContext() {
        currencyService.insert(currency("CNY", "156", "人民币", "¥", 2));
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            rateTypeService.insert(rateType("spot", "Tenant Spot"));
        }

        assertThatThrownBy(() -> conversionService.convert(BigDecimal.ONE,
                "CNY", "CNY", "SPOT", LocalDate.of(2026, 1, 10)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires existing");
    }

    @Test
    void shouldRejectInvalidExchangeRate() {
        prepareCurrenciesAndRateType();

        assertThatThrownBy(() -> rateService.insert(rate("USD", "USD", "SPOT", "2026-01-01", "1")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("different");
        assertThatThrownBy(() -> rateService.insert(rate("USD", "CNY", "SPOT", "2026-01-01", "0")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("positive");
    }

    private void prepareCurrenciesAndRateType() {
        currencyService.insert(currency("USD", "840", "US Dollar", "$", 2));
        currencyService.insert(currency("CNY", "156", "人民币", "¥", 2));
        rateTypeService.insert(rateType("SPOT", "Spot"));
    }

    private Currency currency(String code, String numericCode, String title, String symbol, int scale) {
        Currency currency = new Currency();
        currency.setCode(code);
        currency.setNumericCode(numericCode);
        currency.setTitle(title);
        currency.setSymbol(symbol);
        currency.setDecimalScale(scale);
        return currency;
    }

    private ExchangeRateType rateType(String code, String title) {
        ExchangeRateType rateType = new ExchangeRateType();
        rateType.setCode(code);
        rateType.setTitle(title);
        return rateType;
    }

    private ExchangeRate rate(String from, String to, String type, String effectiveDate, String rateValue) {
        ExchangeRate rate = new ExchangeRate();
        rate.setFromCurrencyCode(from);
        rate.setToCurrencyCode(to);
        rate.setRateTypeCode(type);
        rate.setEffectiveDate(LocalDate.parse(effectiveDate));
        rate.setRate(new BigDecimal(rateValue));
        rate.setTitle(from + "/" + to + " " + type);
        return rate;
    }

    private TenantCurrencySetting setting(String baseCurrencyCode) {
        TenantCurrencySetting setting = new TenantCurrencySetting();
        setting.setBaseCurrencyCode(baseCurrencyCode);
        setting.setTitle("Tenant base currency");
        return setting;
    }
}
