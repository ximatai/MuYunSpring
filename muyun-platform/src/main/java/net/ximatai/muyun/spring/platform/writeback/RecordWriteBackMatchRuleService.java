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
public class RecordWriteBackMatchRuleService extends AbstractAbilityService<RecordWriteBackMatchRule> implements
        SoftDeleteAbility<RecordWriteBackMatchRule>,
        SortAbility<RecordWriteBackMatchRule> {
    public static final String MODULE_ALIAS = "platform.record_write_back_match_rule";
    private static final PageRequest ALL = PageRequest.of(1, 500);

    public RecordWriteBackMatchRuleService(BaseDao<RecordWriteBackMatchRule, String> matchRuleDao) {
        super(MODULE_ALIAS, RecordWriteBackMatchRule.class, matchRuleDao);
    }

    public List<RecordWriteBackMatchRule> selectByRuleId(String ruleId) {
        return list(Criteria.of().eq("ruleId", requireText(ruleId, "ruleId")), ALL, Sort.asc("sortOrder"));
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-match-rule.record-write-back-match-rule-must-not-be-blank",
                    "Record write-back match rule {value1} must not be blank"), Map.of("value1", String.valueOf(fieldName)));
        }
        return value.trim();
    }
}
