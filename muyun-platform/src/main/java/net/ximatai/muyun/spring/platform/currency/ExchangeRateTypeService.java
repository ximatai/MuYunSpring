package net.ximatai.muyun.spring.platform.currency;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.TenantLayerAbility;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedProtectionAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class ExchangeRateTypeService extends StandardBusinessService<ExchangeRateType> implements
        TenantLayerAbility<ExchangeRateType>,
        SoftDeleteAbility<ExchangeRateType>,
        EnableAbility<ExchangeRateType>,
        SortAbility<ExchangeRateType>,
        ReferenceAbility<ExchangeRateType>,
        CacheAbility<ExchangeRateType>,
        PlatformManagedProtectionAbility<ExchangeRateType>,
        QueryAbility<ExchangeRateType> {
    public static final String MODULE_ALIAS = "platform.exchange_rate_type";

    public ExchangeRateTypeService(BaseDao<ExchangeRateType, String> rateTypeDao) {
        super(MODULE_ALIAS, ExchangeRateType.class, rateTypeDao);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, ExchangeRateType.class, java.util.List.of("id", "code", "systemManaged", "tenantId", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("code"));
    }

    @Override
    protected void validateBeforeUpdate(ExchangeRateType rateType, ExchangeRateType existing) {
        validateImmutableIdentity(rateType, existing);
    }

    public ExchangeRateType resolveRateType(String code) {
        return listTenantAndGlobal(Criteria.of().eq("code", requireRateTypeCode(code)),
                Sort.asc(PlatformAbilityFields.SORT_FIELD)).stream().findFirst().orElse(null);
    }

    public ExchangeRateType requireRateType(String rateTypeCode) {
        ExchangeRateType rateType = resolveRateType(rateTypeCode);
        if (rateType == null) {
            throw new PlatformException("Exchange rate type requires existing type: " + rateTypeCode);
        }
        return rateType;
    }

    public ExchangeRateType requireEnabledRateType(String rateTypeCode) {
        ExchangeRateType rateType = requireRateType(rateTypeCode);
        if (!Boolean.TRUE.equals(rateType.getEnabled())) {
            throw new PlatformException("Exchange rate type is disabled: " + rateTypeCode);
        }
        return rateType;
    }

    public List<ExchangeRateType> listRateTypes(boolean enabledOnly) {
        return listVisibleRateTypes(enabledOnly);
    }

    public List<ExchangeRateType> listVisibleRateTypes(boolean enabledOnly) {
        Map<String, ExchangeRateType> records = new LinkedHashMap<>();
        listTenantAndGlobal(Criteria.of(), Sort.asc(PlatformAbilityFields.SORT_FIELD))
                .forEach(record -> records.putIfAbsent(record.getCode(), record));
        // A disabled tenant override still masks the global definition.
        return records.values().stream()
                .filter(record -> !enabledOnly || Boolean.TRUE.equals(record.getEnabled()))
                .toList();
    }

    @Override
    protected void validateBeforeSave(ExchangeRateType rateType) {
        rateType.setCode(requireRateTypeCode(rateType.getCode()));
        if (rateType.getSystemManaged() == null) {
            rateType.setSystemManaged(Boolean.FALSE);
        }
    }

    private void validateImmutableIdentity(ExchangeRateType rateType, ExchangeRateType existing) {
        rejectChanged(existing, rateType, "Exchange rate type code", ExchangeRateType::getCode);
    }

    private String requireRateTypeCode(String value) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("exchangeRateTypeCode must not be blank");
        }
        String code = CurrencyCodeRules.normalizeRateTypeCode(value);
        if (!CurrencyCodeRules.isRateTypeCode(code)) {
            throw new PlatformException("exchangeRateTypeCode must use upper snake code: " + value);
        }
        return code;
    }
}
