package net.ximatai.muyun.spring.platform.writeback;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RecordWriteBackFieldRuleService extends AbstractAbilityService<RecordWriteBackFieldRule> implements
        SoftDeleteAbility<RecordWriteBackFieldRule>,
        SortAbility<RecordWriteBackFieldRule> {
    public static final String MODULE_ALIAS = "platform.record_write_back_field_rule";
    private static final PageRequest ALL = PageRequest.of(1, 500);

    public RecordWriteBackFieldRuleService(BaseDao<RecordWriteBackFieldRule, String> fieldRuleDao) {
        super(MODULE_ALIAS, RecordWriteBackFieldRule.class, fieldRuleDao);
    }

    public List<RecordWriteBackFieldRule> selectByRuleId(String ruleId) {
        return list(Criteria.of().eq("ruleId", requireText(ruleId, "ruleId")), ALL, Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(RecordWriteBackFieldRule rule) {
        normalizeAndValidate(rule);
    }

    @Override
    public void beforeUpdate(RecordWriteBackFieldRule rule) {
        normalizeAndValidate(rule);
    }

    private void normalizeAndValidate(RecordWriteBackFieldRule rule) {
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-field-rule.record-write-back-field-rule-must-not-be-null",
                    "Record write-back field rule must not be null"));
        }
        if (rule.getSourceType() == null) {
            rule.setSourceType(RecordWriteBackFieldSourceType.FIELD);
        }
        if (rule.getOperation() == null) {
            rule.setOperation(RecordWriteBackFieldOperation.COVER);
        }
        if (rule.getSourceType() == RecordWriteBackFieldSourceType.FIELD) {
            rule.setSourceField(requireText(rule.getSourceField(), "sourceField"));
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-field-rule.record-write-back-field-rule-must-not-be-blank",
                    "Record write-back field rule {value1} must not be blank"), Map.of("value1", String.valueOf(fieldName)));
        }
        return value.trim();
    }
}
