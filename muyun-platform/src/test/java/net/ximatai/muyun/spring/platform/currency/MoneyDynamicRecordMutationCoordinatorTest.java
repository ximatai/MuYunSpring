package net.ximatai.muyun.spring.platform.currency;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyDynamicRecordMutationCoordinatorTest {
    private final CurrencyService currencyService = new CurrencyService(new TestMemoryDao<>());
    private final ExchangeRateTypeService rateTypeService = new ExchangeRateTypeService(new TestMemoryDao<>());
    private final ExchangeRateService rateService =
            new ExchangeRateService(new TestMemoryDao<>(), currencyService, rateTypeService);
    private final TenantCurrencySettingService tenantCurrencySettingService =
            new TenantCurrencySettingService(new TestMemoryDao<>(), currencyService);
    private final MoneyDynamicRecordMutationCoordinator coordinator =
            new MoneyDynamicRecordMutationCoordinator(
                    new CurrencyConversionService(currencyService, rateService),
                    tenantCurrencySettingService,
                    Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC)
            );

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldNormalizeSelectableMoneyBeforeDynamicCreate() {
        prepareCurrenciesAndRates();
        DynamicRecord record = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("12.345"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));

        coordinator.beforeCreate("sales.order", "order", record);

        assertThat(baseAmount(record)).isEqualByComparingTo("89.31");
        assertThat(exchangeRate(record)).isEqualByComparingTo("7.2345");
    }

    @Test
    void shouldReuseExistingCurrencyAndRateDateWhenOnlyAmountChangesOnUpdate() {
        prepareCurrenciesAndRates();
        DynamicRecord before = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("10"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));
        coordinator.beforeCreate("sales.order", "order", before);
        DynamicRecord incoming = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("2"));

        coordinator.beforeUpdate("sales.order", "order", before, incoming);

        assertThat(baseAmount(incoming)).isEqualByComparingTo("14.47");
        assertThat(exchangeRate(incoming)).isEqualByComparingTo("7.2345");
    }

    @Test
    void shouldReuseExistingAmountWhenCurrencyChangesOnUpdate() {
        prepareCurrenciesAndRates();
        DynamicRecord before = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("10"))
                .setValue("currencyCode", "CNY")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));
        coordinator.beforeCreate("sales.order", "order", before);
        DynamicRecord incoming = new DynamicRecord(orderEntity())
                .setValue("currencyCode", "USD");

        coordinator.beforeUpdate("sales.order", "order", before, incoming);

        assertThat(baseAmount(incoming)).isEqualByComparingTo("72.35");
        assertThat(exchangeRate(incoming)).isEqualByComparingTo("7.2345");
    }

    @Test
    void shouldRecalculateWhenOnlyRateDateChangesOnUpdate() {
        prepareCurrenciesAndRates();
        DynamicRecord before = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("10"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));
        coordinator.beforeCreate("sales.order", "order", before);
        DynamicRecord incoming = new DynamicRecord(orderEntity())
                .setValue("orderDate", LocalDate.of(2026, 3, 16));

        coordinator.beforeUpdate("sales.order", "order", before, incoming);

        assertThat(baseAmount(incoming)).isEqualByComparingTo("73.00");
        assertThat(exchangeRate(incoming)).isEqualByComparingTo("7.3000");
    }

    @Test
    void shouldSkipWhenUnrelatedFieldChangesOnUpdate() {
        prepareCurrenciesAndRates();
        DynamicRecord before = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("10"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));
        coordinator.beforeCreate("sales.order", "order", before);
        DynamicRecord incoming = new DynamicRecord(orderEntity())
                .setValue("remark", "changed");

        coordinator.beforeUpdate("sales.order", "order", before, incoming);

        assertThat(incoming.getPlatformValues()).doesNotContainKeys("baseAmount", "exchangeRate");
    }

    @Test
    void shouldOverrideExplicitlyChangedGeneratedMoneyFieldsOnUpdate() {
        prepareCurrenciesAndRates();
        DynamicRecord before = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("10"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));
        coordinator.beforeCreate("sales.order", "order", before);
        DynamicRecord incoming = new DynamicRecord(orderEntity())
                .setValue("baseAmount", new BigDecimal("999"))
                .setValue("exchangeRate", new BigDecimal("999"));

        coordinator.beforeUpdate("sales.order", "order", before, incoming);

        assertThat(baseAmount(incoming)).isEqualByComparingTo("72.35");
        assertThat(exchangeRate(incoming)).isEqualByComparingTo("7.2345");
    }

    @Test
    void shouldApplyDefaultSelectableCurrencyBeforeDynamicCreate() {
        prepareCurrenciesAndRates();
        DynamicRecord record = new DynamicRecord(orderEntityWithDefaultCurrency())
                .setValue("amount", new BigDecimal("2"))
                .setValue("orderDate", LocalDate.of(2026, 2, 16));

        coordinator.beforeCreate("sales.order", "order", record);

        assertThat(record.getValue("currencyCode")).isEqualTo("USD");
        assertThat(baseAmount(record)).isEqualByComparingTo("14.47");
    }

    @Test
    void shouldNormalizeExplicitSelectableCurrencyCodeBeforeSave() {
        prepareCurrenciesAndRates();
        DynamicRecord record = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("2"))
                .setValue("currencyCode", " usd ")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));

        coordinator.beforeCreate("sales.order", "order", record);

        assertThat(record.getValue("currencyCode")).isEqualTo("USD");
        assertThat(baseAmount(record)).isEqualByComparingTo("14.47");
    }

    @Test
    void shouldUseOneExchangeRateWhenSourceCurrencyEqualsBaseCurrency() {
        prepareCurrenciesAndRates();
        DynamicRecord record = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("2.345"))
                .setValue("currencyCode", "CNY")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));

        coordinator.beforeCreate("sales.order", "order", record);

        assertThat(baseAmount(record)).isEqualByComparingTo("2.35");
        assertThat(exchangeRate(record)).isEqualByComparingTo("1");
    }

    @Test
    void shouldClearGeneratedValuesWhenAmountIsClearedOnUpdate() {
        prepareCurrenciesAndRates();
        DynamicRecord before = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("10"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));
        coordinator.beforeCreate("sales.order", "order", before);
        DynamicRecord incoming = new DynamicRecord(orderEntity())
                .setValue("amount", null);

        coordinator.beforeUpdate("sales.order", "order", before, incoming);

        assertThat(baseAmount(incoming)).isNull();
        assertThat(exchangeRate(incoming)).isNull();
    }

    @Test
    void shouldUseFixedCurrencyAndCurrentDateWhenNoCompanionFieldsAreConfigured() {
        prepareCurrenciesAndRates();
        DynamicRecord record = new DynamicRecord(fixedCurrencyEntity())
                .setValue("amount", new BigDecimal("3"));

        coordinator.beforeCreate("sales.order", "order", record);

        assertThat(baseAmount(record)).isEqualByComparingTo("21.90");
        assertThat(exchangeRate(record)).isEqualByComparingTo("7.3000");
    }

    @Test
    void shouldUseTenantBaseCurrencyWhenBaseCurrencyIsNotConfigured() {
        prepareCurrenciesAndRates();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantCurrencySettingService.insert(setting("CNY"));
            DynamicRecord record = new DynamicRecord(tenantBaseCurrencyEntity())
                    .setValue("amount", new BigDecimal("2"))
                    .setValue("currencyCode", "USD")
                    .setValue("orderDate", LocalDate.of(2026, 2, 16));

            coordinator.beforeCreate("sales.order", "order", record);

            assertThat(baseAmount(record)).isEqualByComparingTo("14.47");
        }
    }

    @Test
    void shouldResolveTenantBaseCurrencyAndRateFromRecordTenantWhenCurrentContextIsSystem() {
        prepareCurrenciesAndRates();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantCurrencySettingService.insert(setting("CNY"));
            rateService.insert(rate("USD", "CNY", "SPOT", "2026-02-01", "8.0000"));
        }
        DynamicRecord record = new DynamicRecord(tenantBaseCurrencyEntity())
                .setValue("amount", new BigDecimal("2"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));
        record.setTenantId("tenant-a");

        try (TenantContext.Scope ignored = TenantContext.system("system writeback")) {
            coordinator.beforeCreate("sales.order", "order", record);
        }

        assertThat(baseAmount(record)).isEqualByComparingTo("16.00");
        assertThat(exchangeRate(record)).isEqualByComparingTo("8.0000");
    }

    @Test
    void shouldUseBeforeTenantInsteadOfIncomingTenantOnUpdate() {
        prepareCurrenciesAndRates();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantCurrencySettingService.insert(setting("CNY"));
            rateService.insert(rate("USD", "CNY", "SPOT", "2026-02-01", "8.0000"));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            tenantCurrencySettingService.insert(setting("CNY"));
            rateService.insert(rate("USD", "CNY", "SPOT", "2026-02-01", "9.0000"));
        }
        DynamicRecord before = new DynamicRecord(tenantBaseCurrencyEntity())
                .setValue("amount", new BigDecimal("1"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));
        before.setTenantId("tenant-a");
        DynamicRecord incoming = new DynamicRecord(tenantBaseCurrencyEntity())
                .setValue("amount", new BigDecimal("2"));
        incoming.setTenantId("tenant-b");

        try (TenantContext.Scope ignored = TenantContext.system("system writeback")) {
            coordinator.beforeUpdate("sales.order", "order", before, incoming);
        }

        assertThat(baseAmount(incoming)).isEqualByComparingTo("16.00");
        assertThat(exchangeRate(incoming)).isEqualByComparingTo("8.0000");
    }

    @Test
    void shouldNormalizeRelationChildCreateAndUpdate() {
        prepareCurrenciesAndRates();
        DynamicRecord parent = new DynamicRecord(orderEntity());
        DynamicRecord child = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("2"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));

        coordinator.beforeRelationChildCreate("sales.order", "order", "lines", "line", parent, child);

        assertThat(baseAmount(child)).isEqualByComparingTo("14.47");

        DynamicRecord incomingChild = new DynamicRecord(orderEntity())
                .setValue("orderDate", LocalDate.of(2026, 3, 16));
        coordinator.beforeRelationChildUpdate("sales.order", "order", "lines", "line",
                parent, parent, child, incomingChild);

        assertThat(baseAmount(incomingChild)).isEqualByComparingTo("14.60");
        assertThat(exchangeRate(incomingChild)).isEqualByComparingTo("7.3000");
    }

    @Test
    void shouldUseParentTenantWhenRelationChildCreateHasNoTenantInSystemContext() {
        prepareCurrenciesAndRates();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantCurrencySettingService.insert(setting("CNY"));
            rateService.insert(rate("USD", "CNY", "SPOT", "2026-02-01", "8.0000"));
        }
        DynamicRecord parent = new DynamicRecord(tenantBaseCurrencyEntity());
        parent.setTenantId("tenant-a");
        DynamicRecord child = new DynamicRecord(tenantBaseCurrencyEntity())
                .setValue("amount", new BigDecimal("2"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));

        try (TenantContext.Scope ignored = TenantContext.system("system child create")) {
            coordinator.beforeRelationChildCreate("sales.order", "order", "lines", "line", parent, child);
        }

        assertThat(baseAmount(child)).isEqualByComparingTo("16.00");
        assertThat(exchangeRate(child)).isEqualByComparingTo("8.0000");
    }

    @Test
    void shouldRejectMissingRequiredCurrencyWhenMoneyAmountExists() {
        prepareCurrenciesAndRates();
        DynamicRecord record = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("2"))
                .setValue("orderDate", LocalDate.of(2026, 2, 16));

        assertThatThrownBy(() -> coordinator.beforeCreate("sales.order", "order", record))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("currencyCode");
    }

    @Test
    void shouldReportMoneyFieldContextWhenConversionFails() {
        currencyService.insert(currency("USD", "840", "US Dollar", "$", 2));
        currencyService.insert(currency("CNY", "156", "人民币", "¥", 2));
        rateTypeService.insert(rateType("SPOT", "Spot"));
        DynamicRecord record = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("2"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));

        assertThatThrownBy(() -> coordinator.beforeCreate("sales.order", "order", record))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("money normalization failed")
                .hasMessageContaining("module=sales.order")
                .hasMessageContaining("entity=order")
                .hasMessageContaining("field=amount")
                .hasMessageContaining("Exchange rate requires effective rate")
                .isInstanceOfSatisfying(PlatformException.class, error -> {
                    var cause = (PlatformException) error.getCause();
                    assertThat(error.textSource()).isNotNull().isEqualTo(cause.textSource());
                    assertThat(error.messageArgs()).isEqualTo(cause.messageArgs());
                    var catalog = net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
                    var texts = net.ximatai.muyun.spring.common.i18n.TextResources.load(getClass().getClassLoader(), catalog);
                    assertThat(texts.resolve(error.textSource(), error.messageArgs(), "en").text()).startsWith("Exchange rate requires effective rate");
                    assertThat(texts.resolve(error.textSource(), error.messageArgs(), "zh-CN").text()).startsWith("缺少有效汇率");
                    assertThat(error.httpStatus()).isEqualTo(400);
                    assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
                })
                .cause()
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void shouldReportMoneyChildEntityContextWhenRelationChildConversionFails() {
        currencyService.insert(currency("USD", "840", "US Dollar", "$", 2));
        currencyService.insert(currency("CNY", "156", "人民币", "¥", 2));
        rateTypeService.insert(rateType("SPOT", "Spot"));
        DynamicRecord parent = new DynamicRecord(orderEntity());
        DynamicRecord child = new DynamicRecord(orderEntity())
                .setValue("amount", new BigDecimal("2"))
                .setValue("currencyCode", "USD")
                .setValue("orderDate", LocalDate.of(2026, 2, 16));

        assertThatThrownBy(() -> coordinator.beforeRelationChildCreate("sales.order", "order", "lines", "line",
                parent, child))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("money normalization failed")
                .hasMessageContaining("module=sales.order")
                .hasMessageContaining("entity=line")
                .hasMessageContaining("field=amount")
                .cause()
                .isInstanceOf(PlatformException.class);
    }

    private EntityDefinition orderEntity() {
        return orderEntity(null, "CNY", "orderDate");
    }

    private EntityDefinition orderEntityWithDefaultCurrency() {
        return orderEntity("USD", "CNY", "orderDate");
    }

    private EntityDefinition tenantBaseCurrencyEntity() {
        return orderEntity(null, null, "orderDate");
    }

    private EntityDefinition fixedCurrencyEntity() {
        return new EntityDefinition(
                "order",
                "sales_order",
                "Order",
                List.of(
                        FieldDefinition.decimal("amount", "Amount").money(new FieldMoneyDefinition(
                                FieldMoneyMode.FIXED,
                                "USD",
                                null,
                                null,
                                "baseAmount",
                                "CNY",
                                "SPOT",
                                null,
                                "exchangeRate",
                                false
                        )),
                        FieldDefinition.decimal("baseAmount", "Base Amount").column("base_amount"),
                        FieldDefinition.decimal("exchangeRate", "Exchange Rate").column("exchange_rate")
                ),
                Set.of(EntityCapability.CRUD)
        );
    }

    private EntityDefinition orderEntity(String defaultCurrencyCode,
                                         String baseCurrencyCode,
                                         String rateDateFieldName) {
        return new EntityDefinition(
                "order",
                "sales_order",
                "Order",
                List.of(
                        FieldDefinition.decimal("amount", "Amount").money(new FieldMoneyDefinition(
                                FieldMoneyMode.SELECTABLE,
                                null,
                                defaultCurrencyCode,
                                "currencyCode",
                                "baseAmount",
                                baseCurrencyCode,
                                "SPOT",
                                rateDateFieldName,
                                "exchangeRate",
                                true
                        )),
                        FieldDefinition.string("currencyCode", "Currency").column("currency_code").length(3),
                        FieldDefinition.decimal("baseAmount", "Base Amount").column("base_amount"),
                        FieldDefinition.of("orderDate", FieldType.DATE, "Order Date").column("order_date"),
                        FieldDefinition.decimal("exchangeRate", "Exchange Rate").column("exchange_rate"),
                        FieldDefinition.string("remark", "Remark").column("remark").length(255)
                ),
                Set.of(EntityCapability.CRUD)
        );
    }

    private void prepareCurrenciesAndRates() {
        currencyService.insert(currency("USD", "840", "US Dollar", "$", 2));
        currencyService.insert(currency("CNY", "156", "人民币", "¥", 2));
        rateTypeService.insert(rateType("SPOT", "Spot"));
        rateService.insert(rate("USD", "CNY", "SPOT", "2026-02-01", "7.2345"));
        rateService.insert(rate("USD", "CNY", "SPOT", "2026-03-01", "7.3000"));
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

    private BigDecimal baseAmount(DynamicRecord record) {
        return (BigDecimal) record.getValue("baseAmount");
    }

    private BigDecimal exchangeRate(DynamicRecord record) {
        return (BigDecimal) record.getValue("exchangeRate");
    }
}
