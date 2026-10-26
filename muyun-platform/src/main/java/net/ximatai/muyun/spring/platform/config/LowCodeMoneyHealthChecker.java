package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.platform.currency.CurrencyCodeRules;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class LowCodeMoneyHealthChecker implements LowCodeModuleHealthChecker {
    private static final Set<String> NUMERIC_TYPES = Set.of("DECIMAL", "NUMERIC", "NUMBER", "INTEGER", "INT",
            "LONG", "BIGINT");
    private static final Set<String> TEXT_TYPES = Set.of("STRING", "TEXT", "VARCHAR", "CHAR");
    private static final Set<String> DATE_TYPES = Set.of("DATE", "TIMESTAMP", "ZONED_TIMESTAMP", "DATETIME");

    @Override
    public List<LowCodeConfigHealthItem> check(LowCodeModuleHealthContext context) {
        LowCodeModulePackage modulePackage = context == null ? null : context.modulePackage();
        if (modulePackage == null || !modulePackage.includes(LowCodePackageBundleType.METADATA)) {
            return List.of();
        }
        LowCodeConfigBundle bundle = modulePackage.bundleMap().get(LowCodePackageBundleType.METADATA);
        if (bundle == null || bundle.content().isEmpty()) {
            return List.of();
        }
        List<FieldContract> fields = LowCodeMetadataFieldProjection.from(bundle).stream()
                .map(this::contract)
                .toList();
        if (fields.isEmpty()) {
            return List.of();
        }
        Set<String> fieldNames = fields.stream()
                .map(FieldContract::fieldName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> currencyDependencies = dependencies(modulePackage, LowCodePackageDependencyType.CURRENCY);
        Set<String> rateTypeDependencies = dependencies(modulePackage, LowCodePackageDependencyType.EXCHANGE_RATE_TYPE);
        Map<String, String> fieldTypes = fields.stream()
                .filter(field -> field.fieldName() != null)
                .filter(field -> field.fieldType() != null)
                .collect(Collectors.toMap(FieldContract::fieldName, FieldContract::fieldType, (left, right) -> left));

        List<LowCodeConfigHealthItem> items = new ArrayList<>();
        Set<String> reportedCurrencyDependencies = new HashSet<>();
        Set<String> reportedRateTypeDependencies = new HashSet<>();
        for (FieldContract field : fields) {
            if (!field.moneyConfigured()) {
                continue;
            }
            String targetId = field.fieldName() == null ? "money" : field.fieldName();
            requireType(items,
                field.fieldType(),
                NUMERIC_TYPES,
                "MONEY_OWNER_NOT_NUMERIC",
                DiagnosticText.code("platform.config.diagnostic.money-field-requires-numeric-owner",
                    "money field requires numeric owner"),
                targetId);
            if (field.currencyMode() == null) {
                items.add(error("MONEY_CURRENCY_MODE_MISSING",
                DiagnosticText.code("platform.config.diagnostic.money-field-requires-currencymode",
                    "money field requires currencyMode"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.set-currencymode-to-fixed-or-selectable",
                    "Set currencyMode to FIXED or SELECTABLE")));
            } else if ("FIXED".equals(field.currencyMode())) {
                requireFixedCurrencyCode(items, field.fixedCurrencyCode(), targetId);
            } else if ("SELECTABLE".equals(field.currencyMode())) {
                requireRelatedField(items,
                field.currencyFieldName(),
                fieldNames,
                "MONEY_CURRENCY_COMPANION_MISSING",
                DiagnosticText.code("platform.config.diagnostic.selectable-money-field-requires-currency-companion-field",
                    "selectable money field requires currency companion field"),
                targetId);
                requireRelatedType(items,
                field.currencyFieldName(),
                fieldTypes,
                TEXT_TYPES,
                "MONEY_CURRENCY_COMPANION_NOT_TEXT",
                DiagnosticText.code("platform.config.diagnostic.money-currency-companion-field-must-be-text",
                    "money currency companion field must be text"),
                targetId);
            } else {
                items.add(error("MONEY_CURRENCY_MODE_INVALID",
                DiagnosticText.code("platform.config.diagnostic.money-currencymode-must-be-fixed-or-selectable",
                    "money currencyMode must be FIXED or SELECTABLE"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.set-currencymode-to-fixed-or-selectable",
                    "Set currencyMode to FIXED or SELECTABLE")));
            }
            requireCurrencyCode(items, field.defaultCurrencyCode(), "MONEY_DEFAULT_CURRENCY_INVALID", DiagnosticText.code("platform.config.diagnostic.money-defaultcurrencycode-must-be-iso-4217-alpha-3-code",
                    "money defaultCurrencyCode must be ISO 4217 alpha-3 code"), targetId);
            requireCurrencyCode(items, field.baseCurrencyCode(), "MONEY_BASE_CURRENCY_INVALID", DiagnosticText.code("platform.config.diagnostic.money-basecurrencycode-must-be-iso-4217-alpha-3-code",
                    "money baseCurrencyCode must be ISO 4217 alpha-3 code"), targetId);
            requireBaseAmountField(items, field, fieldNames, fieldTypes, targetId);
            requireRateTypeCode(items, field.rateTypeCode(), targetId);
            requireCurrencyDependencies(items, field, currencyDependencies, reportedCurrencyDependencies);
            requireRateTypeDependency(items, field.rateTypeCode(), rateTypeDependencies, reportedRateTypeDependencies);
            requireOptionalRelatedField(items,
                field.rateDateFieldName(),
                fieldNames,
                "MONEY_RATE_DATE_FIELD_MISSING",
                DiagnosticText.code("platform.config.diagnostic.money-rate-date-field-is-missing",
                    "money rate date field is missing"),
                targetId);
            requireRelatedType(items,
                field.rateDateFieldName(),
                fieldTypes,
                DATE_TYPES,
                "MONEY_RATE_DATE_FIELD_NOT_DATE",
                DiagnosticText.code("platform.config.diagnostic.money-rate-date-field-must-be-date-or-timestamp",
                    "money rate date field must be date or timestamp"),
                targetId);
            requireOptionalRelatedField(items,
                field.exchangeRateFieldName(),
                fieldNames,
                "MONEY_EXCHANGE_RATE_FIELD_MISSING",
                DiagnosticText.code("platform.config.diagnostic.money-exchange-rate-field-is-missing",
                    "money exchange rate field is missing"),
                targetId);
            requireRelatedType(items,
                field.exchangeRateFieldName(),
                fieldTypes,
                NUMERIC_TYPES,
                "MONEY_EXCHANGE_RATE_FIELD_NOT_NUMERIC",
                DiagnosticText.code("platform.config.diagnostic.money-exchange-rate-field-must-be-numeric",
                    "money exchange rate field must be numeric"),
                targetId);
        }
        return List.copyOf(items);
    }

    private Set<String> dependencies(LowCodeModulePackage modulePackage, LowCodePackageDependencyType type) {
        return modulePackage.dependencyManifest().dependencies().stream()
                .filter(Objects::nonNull)
                .filter(dependency -> dependency.type() == type)
                .map(LowCodePackageDependency::alias)
                .filter(Objects::nonNull)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    private void requireCurrencyDependencies(List<LowCodeConfigHealthItem> items,
                                             FieldContract field,
                                             Set<String> dependencies,
                                             Set<String> reportedDependencies) {
        Set<String> requiredCodes = Stream.of(
                        field.fixedCurrencyCode(),
                        field.defaultCurrencyCode(),
                        field.baseCurrencyCode()
                )
                .filter(Objects::nonNull)
                .map(CurrencyCodeRules::normalizeCurrencyCode)
                .filter(CurrencyCodeRules::isCurrencyCode)
                .collect(Collectors.toSet());
        for (String currencyCode : requiredCodes) {
            if (!dependencies.contains(currencyCode) && reportedDependencies.add(currencyCode)) {
                items.add(warn("MONEY_CURRENCY_DEPENDENCY_MISSING",
                DiagnosticText.code("platform.config.diagnostic.money-currency-is-not-declared-in-dependency-manifest",
                    "money currency is not declared in dependency manifest"),
                "currency",
                currencyCode,
                DiagnosticText.code("platform.config.diagnostic.declare-currency-dependency-for-cross-environment-migration",
                    "Declare CURRENCY dependency {currencyCode} for cross-environment migration", Map.of("currencyCode", String.valueOf(currencyCode)))));
            }
        }
    }

    private void requireRateTypeDependency(List<LowCodeConfigHealthItem> items,
                                           String rateTypeCode,
                                           Set<String> dependencies,
                                           Set<String> reportedDependencies) {
        if (rateTypeCode == null) {
            return;
        }
        String normalized = CurrencyCodeRules.normalizeRateTypeCode(rateTypeCode);
        if (CurrencyCodeRules.isRateTypeCode(normalized) && !dependencies.contains(normalized)
                && reportedDependencies.add(normalized)) {
            items.add(warn("MONEY_RATE_TYPE_DEPENDENCY_MISSING",
                DiagnosticText.code("platform.config.diagnostic.money-exchange-rate-type-is-not-declared-in-dependency-manifest",
                    "money exchange rate type is not declared in dependency manifest"),
                "exchangeRateType",
                normalized,
                DiagnosticText.code("platform.config.diagnostic.declare-exchange-rate-type-dependency-for-cross-environment-migration",
                    "Declare EXCHANGE_RATE_TYPE dependency {normalized} for cross-environment migration", Map.of("normalized", String.valueOf(normalized)))));
        }
    }

    private void requireBaseAmountField(List<LowCodeConfigHealthItem> items,
                                        FieldContract field,
                                        Set<String> fieldNames,
                                        Map<String, String> fieldTypes,
                                        String targetId) {
        String baseAmountFieldName = field.baseAmountFieldName();
        String ownerFieldName = field.fieldName();
        if (baseAmountFieldName == null) {
            items.add(error("MONEY_BASE_AMOUNT_MISSING",
                DiagnosticText.code("platform.config.diagnostic.money-field-requires-base-amount-field",
                    "money field requires base amount field"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.add-a-shadow-base-amount-field-and-bind-baseamountfieldname",
                    "Add a shadow base amount field and bind baseAmountFieldName")));
            return;
        }
        if (Objects.equals(ownerFieldName, baseAmountFieldName)
                || (field.ownerFieldId() != null && field.baseAmountFieldId() != null
                && Objects.equals(field.ownerFieldId(), field.baseAmountFieldId()))) {
            items.add(error("MONEY_BASE_AMOUNT_CONFLICT",
                DiagnosticText.code("platform.config.diagnostic.money-base-amount-field-must-be-different-from-owner",
                    "money base amount field must be different from owner"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.use-a-dedicated-shadow-field-such-as-base",
                    "Use a dedicated shadow field such as {ownerFieldName}Base", Map.of("ownerFieldName", String.valueOf(ownerFieldName)))));
            return;
        }
        if (!fieldNames.contains(baseAmountFieldName)) {
            items.add(error("MONEY_BASE_AMOUNT_MISSING",
                DiagnosticText.code("platform.config.diagnostic.money-base-amount-field-is-missing-from-metadata-fields",
                    "money base amount field is missing from metadata fields"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.include-the-shadow-base-amount-field-in-the-metadata-bundle",
                    "Include the shadow base amount field in the metadata bundle")));
            return;
        }
        requireRelatedType(items,
                baseAmountFieldName,
                fieldTypes,
                NUMERIC_TYPES,
                "MONEY_BASE_AMOUNT_NOT_NUMERIC",
                DiagnosticText.code("platform.config.diagnostic.money-base-amount-field-must-be-numeric",
                    "money base amount field must be numeric"),
                targetId);
    }

    private void requireRelatedField(List<LowCodeConfigHealthItem> items,
                                     String fieldName,
                                     Set<String> fieldNames,
                                     String code,
                                     DiagnosticText message,
                                     String targetId) {
        if (fieldName == null || !fieldNames.contains(fieldName)) {
            items.add(error(code,
                message,
                targetId,
                DiagnosticText.code("platform.config.diagnostic.include-and-bind-related-field",
                    "Include and bind related field")));
        }
    }

    private void requireOptionalRelatedField(List<LowCodeConfigHealthItem> items,
                                             String fieldName,
                                             Set<String> fieldNames,
                                             String code,
                                             DiagnosticText message,
                                             String targetId) {
        if (fieldName != null && !fieldNames.contains(fieldName)) {
            items.add(error(code,
                message,
                targetId,
                DiagnosticText.code("platform.config.diagnostic.include-or-remove-related-field",
                    "Include or remove related field")));
        }
    }

    private void requireRelatedType(List<LowCodeConfigHealthItem> items,
                                    String fieldName,
                                    Map<String, String> fieldTypes,
                                    Set<String> expectedTypes,
                                    String code,
                                    DiagnosticText message,
                                    String targetId) {
        if (fieldName == null) {
            return;
        }
        requireType(items, fieldTypes.get(fieldName), expectedTypes, code, message, targetId);
    }

    private void requireType(List<LowCodeConfigHealthItem> items,
                             String fieldType,
                             Set<String> expectedTypes,
                             String code,
                             DiagnosticText message,
                             String targetId) {
        if (fieldType != null && !expectedTypes.contains(fieldType)) {
            items.add(error(code,
                message,
                targetId,
                DiagnosticText.code("platform.config.diagnostic.use-a-compatible-metadata-field-type",
                    "Use a compatible metadata field type")));
        }
    }

    private void requireFixedCurrencyCode(List<LowCodeConfigHealthItem> items, String value, String targetId) {
        if (value == null) {
            items.add(error("MONEY_FIXED_CURRENCY_MISSING",
                DiagnosticText.code("platform.config.diagnostic.fixed-money-field-requires-fixedcurrencycode",
                    "fixed money field requires fixedCurrencyCode"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.set-fixedcurrencycode",
                    "Set fixedCurrencyCode")));
            return;
        }
        if (!CurrencyCodeRules.isCurrencyCode(value)) {
            items.add(error("MONEY_FIXED_CURRENCY_INVALID",
                DiagnosticText.code("platform.config.diagnostic.money-fixedcurrencycode-must-be-iso-4217-alpha-3-code",
                    "money fixedCurrencyCode must be ISO 4217 alpha-3 code"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.use-iso-4217-alpha-3-currency-code",
                    "Use ISO 4217 alpha-3 currency code")));
        }
    }

    private void requireCurrencyCode(List<LowCodeConfigHealthItem> items,
                                     String value,
                                     String code,
                                     DiagnosticText message,
                                     String targetId) {
        if (value != null && !CurrencyCodeRules.isCurrencyCode(value)) {
            items.add(error(code,
                message,
                targetId,
                DiagnosticText.code("platform.config.diagnostic.use-iso-4217-alpha-3-currency-code",
                    "Use ISO 4217 alpha-3 currency code")));
        }
    }

    private void requireRateTypeCode(List<LowCodeConfigHealthItem> items, String value, String targetId) {
        if (value == null) {
            items.add(error("MONEY_RATE_TYPE_MISSING",
                DiagnosticText.code("platform.config.diagnostic.money-field-requires-ratetypecode",
                    "money field requires rateTypeCode"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.set-exchange-rate-type-code",
                    "Set exchange rate type code")));
            return;
        }
        if (!CurrencyCodeRules.isRateTypeCode(value)) {
            items.add(error("MONEY_RATE_TYPE_INVALID",
                DiagnosticText.code("platform.config.diagnostic.money-ratetypecode-must-use-upper-snake-code",
                    "money rateTypeCode must use upper snake code"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.use-upper-snake-exchange-rate-type-code",
                    "Use upper snake exchange rate type code")));
        }
    }

    private LowCodeConfigHealthItem error(String code,
                DiagnosticText message,
                String targetId,
                DiagnosticText suggestion) {
        return LowCodeConfigHealthItem.error(LowCodeConfigHealthScope.METADATA,
                code,
                message,
                "field",
                targetId,
                suggestion);
    }

    private LowCodeConfigHealthItem warn(String code,
                DiagnosticText message,
                String targetType,
                String targetId,
                DiagnosticText suggestion) {
        return LowCodeConfigHealthItem.warn(LowCodeConfigHealthScope.DEPENDENCY,
                code,
                message,
                targetType,
                targetId,
                suggestion);
    }

    private FieldContract contract(LowCodeMetadataFieldProjection field) {
        Map<String, Object> money = field.nested("money");
        String baseAmountFieldId = field.firstText(money, "moneyBaseAmountFieldId", "baseAmountFieldId");
        return new FieldContract(
                field.fieldName(),
                field.ownerFieldId(),
                field.runtimeFieldType(),
                moneyConfigured(field, money),
                field.firstText(money, "moneyCurrencyMode", "currencyMode", "mode"),
                field.firstText(money, "moneyFixedCurrencyCode", "fixedCurrencyCode"),
                field.firstText(money, "moneyDefaultCurrencyCode", "defaultCurrencyCode"),
                field.relatedFieldName(money, "moneyCurrencyFieldName", "currencyFieldName",
                        "moneyCurrencyFieldId", "currencyFieldId"),
                field.relatedFieldName(money, "moneyBaseAmountFieldName", "baseAmountFieldName",
                        "moneyBaseAmountFieldId", "baseAmountFieldId"),
                baseAmountFieldId,
                field.firstText(money, "moneyBaseCurrencyCode", "baseCurrencyCode"),
                field.firstText(money, "moneyRateTypeCode", "rateTypeCode"),
                field.relatedFieldName(money, "moneyRateDateFieldName", "rateDateFieldName",
                        "moneyRateDateFieldId", "rateDateFieldId"),
                field.relatedFieldName(money, "moneyExchangeRateFieldName", "exchangeRateFieldName",
                        "moneyExchangeRateFieldId", "exchangeRateFieldId")
        );
    }

    private boolean moneyConfigured(LowCodeMetadataFieldProjection field, Map<String, Object> money) {
        return field.firstText(money, "moneyCurrencyMode", "currencyMode", "mode") != null
                || field.firstText(money, "moneyFixedCurrencyCode", "fixedCurrencyCode") != null
                || field.firstText(money, "moneyDefaultCurrencyCode", "defaultCurrencyCode") != null
                || field.firstText(money, "moneyBaseAmountFieldId", "baseAmountFieldId",
                "moneyBaseAmountFieldName", "baseAmountFieldName") != null
                || field.firstText(money, "moneyBaseCurrencyCode", "baseCurrencyCode") != null
                || field.firstText(money, "moneyRateTypeCode", "rateTypeCode") != null;
    }

    private record FieldContract(
            String fieldName,
            String ownerFieldId,
            String fieldType,
            boolean moneyConfigured,
            String currencyMode,
            String fixedCurrencyCode,
            String defaultCurrencyCode,
            String currencyFieldName,
            String baseAmountFieldName,
            String baseAmountFieldId,
            String baseCurrencyCode,
            String rateTypeCode,
            String rateDateFieldName,
            String exchangeRateFieldName
    ) {
    }
}
