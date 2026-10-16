package net.ximatai.muyun.spring.platform.currency;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyMode;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordMutationCoordinator;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 90)
public class MoneyDynamicRecordMutationCoordinator implements DynamicRecordMutationCoordinator {
    private final CurrencyConversionService conversionService;
    private final TenantCurrencySettingService tenantCurrencySettingService;
    private final Clock clock;

    @Autowired
    public MoneyDynamicRecordMutationCoordinator(CurrencyConversionService conversionService,
                                                 TenantCurrencySettingService tenantCurrencySettingService) {
        this(conversionService, tenantCurrencySettingService, Clock.systemDefaultZone());
    }

    public MoneyDynamicRecordMutationCoordinator(CurrencyConversionService conversionService,
                                                 TenantCurrencySettingService tenantCurrencySettingService,
                                                 Clock clock) {
        this.conversionService = conversionService;
        this.tenantCurrencySettingService = tenantCurrencySettingService;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public void beforeCreate(String moduleAlias, String entityAlias, DynamicRecord record) {
        normalizeMoneyFields(moduleAlias, entityAlias, record, null, null, true);
    }

    @Override
    public void beforeRelationChildCreate(String moduleAlias,
                                          String parentEntityAlias,
                                          String relationCode,
                                          String childEntityAlias,
                                          DynamicRecord parent,
                                          DynamicRecord child) {
        normalizeMoneyFields(moduleAlias, childEntityAlias, child, null, parent, true);
    }

    @Override
    public void beforeUpdate(String moduleAlias, String entityAlias, DynamicRecord before, DynamicRecord incoming) {
        normalizeMoneyFields(moduleAlias, entityAlias, incoming, before, null, false);
    }

    @Override
    public void beforeRelationChildUpdate(String moduleAlias,
                                          String parentEntityAlias,
                                          String relationCode,
                                          String childEntityAlias,
                                          DynamicRecord parentBefore,
                                          DynamicRecord parentIncoming,
                                          DynamicRecord childBefore,
                                          DynamicRecord childIncoming) {
        normalizeMoneyFields(moduleAlias, childEntityAlias, childIncoming, childBefore, parentBefore, false);
    }

    private void normalizeMoneyFields(String moduleAlias,
                                      String entityAlias,
                                      DynamicRecord incoming,
                                      DynamicRecord before,
                                      DynamicRecord parent,
                                      boolean create) {
        if (incoming == null) {
            return;
        }
        for (FieldDefinition field : incoming.getEntity().fields()) {
            FieldMoneyDefinition money = field.money();
            if (money == null || !money.enabled()) {
                continue;
            }
            try {
                normalizeMoneyField(incoming, before, parent, create, field, money);
            } catch (PlatformException ex) {
                throw new PlatformException(net.ximatai.muyun.spring.common.exception.PlatformErrorCodes.VALIDATION_FAILED, 400, "money normalization failed: module=" + moduleAlias
                        + ", entity=" + entityAlias
                        + ", field=" + field.fieldName()
                        + "; " + ex.getMessage(), ex, net.ximatai.muyun.spring.common.exception.ErrorScope.empty(),
                        java.util.List.of(), Map.of(), ex.messageArgs(), ex.textSource());
            }
        }
    }

    private void normalizeMoneyField(DynamicRecord incoming,
                                     DynamicRecord before,
                                     DynamicRecord parent,
                                     boolean create,
                                     FieldDefinition field,
                                     FieldMoneyDefinition money) {
        boolean amountChanged = create || incoming.isExplicitlySet(field.fieldName());
        boolean currencyChanged = create || isSelectableCurrencyChanged(incoming, money);
        boolean rateDateChanged = create || isRateDateChanged(incoming, money);
        boolean generatedChanged = incoming.isExplicitlySet(money.baseAmountFieldName())
                || money.exchangeRateFieldName() != null
                && incoming.isExplicitlySet(money.exchangeRateFieldName());
        if (!amountChanged && !currencyChanged && !rateDateChanged && !generatedChanged) {
            return;
        }
        Object amountValue = amountChanged
                ? incoming.getPlatformValues().get(field.fieldName())
                : valueOf(before, field.fieldName());
        if (amountValue == null) {
            clearConversionValues(incoming, money);
            return;
        }
        String sourceCurrencyCode = resolveSourceCurrencyCode(incoming, before, money);
        if (sourceCurrencyCode == null) {
            clearConversionValues(incoming, money);
            return;
        }
        CurrencyConversion conversion = convertInRecordTenantScope(
                incoming,
                before,
                parent,
                create,
                bigDecimal(amountValue),
                sourceCurrencyCode,
                money
        );
        incoming.putGeneratedValue(money.baseAmountFieldName(), conversion.convertedAmount());
        if (money.exchangeRateFieldName() != null && !money.exchangeRateFieldName().isBlank()) {
            incoming.putGeneratedValue(money.exchangeRateFieldName(), conversion.exchangeRate());
        }
    }

    private boolean isSelectableCurrencyChanged(DynamicRecord incoming, FieldMoneyDefinition money) {
        return money.currencyMode() == FieldMoneyMode.SELECTABLE
                && money.currencyFieldName() != null
                && incoming.isExplicitlySet(money.currencyFieldName());
    }

    private boolean isRateDateChanged(DynamicRecord incoming, FieldMoneyDefinition money) {
        return money.rateDateFieldName() != null
                && !money.rateDateFieldName().isBlank()
                && incoming.isExplicitlySet(money.rateDateFieldName());
    }

    private String resolveSourceCurrencyCode(DynamicRecord incoming,
                                             DynamicRecord before,
                                             FieldMoneyDefinition money) {
        if (money.currencyMode() == FieldMoneyMode.FIXED) {
            return money.fixedCurrencyCode();
        }
        String currencyFieldName = money.currencyFieldName();
        boolean currencyExplicitlySet = incoming.isExplicitlySet(currencyFieldName);
        String currencyCode = stringValue(incoming.getPlatformValues().get(currencyFieldName));
        if (currencyExplicitlySet && currencyCode == null) {
            if (money.currencyRequired()) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.money-dynamic-record-mutation-coordinator.money-currency-field-must-not-be-blank-when-money-amount-exists",
                        "money currency field must not be blank when money amount exists: {value1}"), Map.of("value1", String.valueOf(currencyFieldName)));
            }
            return null;
        }
        if (currencyCode == null && before != null) {
            currencyCode = stringValue(valueOf(before, currencyFieldName));
        }
        if (currencyCode == null && money.defaultCurrencyCode() != null && !money.defaultCurrencyCode().isBlank()) {
            currencyCode = money.defaultCurrencyCode();
            incoming.putGeneratedValue(currencyFieldName, currencyCode);
        }
        if (currencyCode == null && money.currencyRequired()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.money-dynamic-record-mutation-coordinator.money-currency-field-is-required-when-money-amount-exists",
                    "money currency field is required when money amount exists: {value1}"), Map.of("value1", String.valueOf(currencyFieldName)));
        }
        if (currencyCode == null) {
            return null;
        }
        String normalized = currencyCode.trim().toUpperCase();
        if (!normalized.equals(currencyCode) || !Objects.equals(incoming.getPlatformValues().get(currencyFieldName), normalized)) {
            incoming.putGeneratedValue(currencyFieldName, normalized);
        }
        return normalized;
    }

    private String resolveBaseCurrencyCode(FieldMoneyDefinition money) {
        if (money.baseCurrencyCode() != null && !money.baseCurrencyCode().isBlank()) {
            return money.baseCurrencyCode();
        }
        return tenantCurrencySettingService.requireCurrentBaseCurrencyCode();
    }

    private CurrencyConversion convertInRecordTenantScope(DynamicRecord incoming,
                                                          DynamicRecord before,
                                                          DynamicRecord parent,
                                                          boolean create,
                                                          BigDecimal amount,
                                                          String sourceCurrencyCode,
                                                          FieldMoneyDefinition money) {
        String tenantId = recordTenantId(incoming, before, parent, create);
        if (tenantId == null) {
            return convert(amount, sourceCurrencyCode, money, incoming, before);
        }
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            return convert(amount, sourceCurrencyCode, money, incoming, before);
        }
    }

    private CurrencyConversion convert(BigDecimal amount,
                                       String sourceCurrencyCode,
                                       FieldMoneyDefinition money,
                                       DynamicRecord incoming,
                                       DynamicRecord before) {
        String baseCurrencyCode = resolveBaseCurrencyCode(money);
        LocalDate rateDate = resolveRateDate(incoming, before, money);
        return conversionService.convert(
                amount,
                sourceCurrencyCode,
                baseCurrencyCode,
                money.rateTypeCode(),
                rateDate
        );
    }

    private String recordTenantId(DynamicRecord incoming,
                                  DynamicRecord before,
                                  DynamicRecord parent,
                                  boolean create) {
        String tenantId = create
                ? stringValue(incoming == null ? null : incoming.getTenantId())
                : stringValue(before == null ? null : before.getTenantId());
        if (tenantId == null && create) {
            tenantId = stringValue(parent == null ? null : parent.getTenantId());
        }
        if (tenantId == null && !create) {
            tenantId = stringValue(incoming == null ? null : incoming.getTenantId());
        }
        if (tenantId == null) {
            tenantId = stringValue(parent == null ? null : parent.getTenantId());
        }
        return tenantId;
    }

    private LocalDate resolveRateDate(DynamicRecord incoming,
                                      DynamicRecord before,
                                      FieldMoneyDefinition money) {
        String rateDateFieldName = money.rateDateFieldName();
        if (rateDateFieldName == null || rateDateFieldName.isBlank()) {
            return LocalDate.now(clock);
        }
        Object value = incoming.getPlatformValues().get(rateDateFieldName);
        if (value == null && !incoming.isExplicitlySet(rateDateFieldName)) {
            value = valueOf(before, rateDateFieldName);
        }
        return value == null ? LocalDate.now(clock) : localDate(value, clock.getZone());
    }

    private void clearConversionValues(DynamicRecord incoming, FieldMoneyDefinition money) {
        incoming.putGeneratedValue(money.baseAmountFieldName(), null);
        if (money.exchangeRateFieldName() != null && !money.exchangeRateFieldName().isBlank()) {
            incoming.putGeneratedValue(money.exchangeRateFieldName(), null);
        }
    }

    private Object valueOf(DynamicRecord record, String fieldName) {
        return record == null ? null : record.getPlatformValues().get(fieldName);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private BigDecimal bigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(Objects.toString(value));
    }

    private LocalDate localDate(Object value, ZoneId zoneId) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Instant instant) {
            return LocalDate.ofInstant(instant, zoneId);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.util.Date date) {
            return LocalDate.ofInstant(date.toInstant(), zoneId);
        }
        return LocalDate.parse(String.valueOf(value).trim());
    }
}
