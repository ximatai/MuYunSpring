package net.ximatai.muyun.spring.platform.duplicate;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class RecordDuplicateRuleService extends AbstractAbilityService<RecordDuplicateRule> implements
        SoftDeleteAbility<RecordDuplicateRule>,
        EnableAbility<RecordDuplicateRule> {
    public static final String MODULE_ALIAS = "platform.record_duplicate_rule";

    public RecordDuplicateRuleService(BaseDao<RecordDuplicateRule, String> ruleDao) {
        super(MODULE_ALIAS, RecordDuplicateRule.class, ruleDao);
    }

    public RecordDuplicateRule requireEnabledRule(String moduleAlias, String actionCode) {
        RecordDuplicateRule rule = findOne(Criteria.of()
                .eq("moduleAlias", requireText(moduleAlias, "moduleAlias"))
                .eq("actionCode", requireActionCode(actionCode))
                .eq("enabled", Boolean.TRUE));
        if (rule == null) {
            throw new PlatformException("duplicate rule is not enabled for action: " + actionCode);
        }
        return rule;
    }

    public List<String> fieldNames(RecordDuplicateRule rule) {
        if (rule == null || rule.getFieldNames() == null || rule.getFieldNames().isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(rule.getFieldNames().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    @Override
    public void beforeInsert(RecordDuplicateRule rule) {
        normalizeAndValidate(rule);
        rejectDuplicate(rule, scopeCriteria(rule),
                "duplicate rule actionCode must be unique in module: "
                        + rule.getModuleAlias() + "." + rule.getActionCode());
    }

    @Override
    public void beforeUpdate(RecordDuplicateRule rule) {
        normalizeAndValidate(rule);
        rejectDuplicate(rule, scopeCriteria(rule),
                "duplicate rule actionCode must be unique in module: "
                        + rule.getModuleAlias() + "." + rule.getActionCode());
    }

    private Criteria scopeCriteria(RecordDuplicateRule rule) {
        return Criteria.of()
                .eq("moduleAlias", rule.getModuleAlias())
                .eq("actionCode", rule.getActionCode());
    }

    private void normalizeAndValidate(RecordDuplicateRule rule) {
        if (rule == null) {
            throw new PlatformException("duplicate rule must not be null");
        }
        rule.setActionCode(requireActionCode(rule.getActionCode()));
        List<String> fields = normalizeFieldNames(rule.getFieldNames());
        if (fields.isEmpty()) {
            throw new PlatformException("duplicate rule requires fields: " + rule.getActionCode());
        }
        rule.setFieldNames(String.join(",", fields));
        if (rule.getEnabled() == null) {
            rule.setEnabled(Boolean.TRUE);
        }
    }

    private List<String> normalizeFieldNames(String fieldNames) {
        if (fieldNames == null || fieldNames.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : fieldNames.split(",")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String fieldName = requireFieldName(raw.trim());
            if (!normalized.add(fieldName)) {
                throw new PlatformException("duplicate rule field duplicated: " + fieldName);
            }
        }
        return List.copyOf(normalized);
    }

    private String requireActionCode(String actionCode) {
        return PlatformNameRules.requireIdentifier(actionCode, "actionCode");
    }

    private String requireFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank() || fieldName.contains(",")) {
            throw new PlatformException("duplicate rule field name is invalid: " + fieldName);
        }
        return fieldName.trim();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("duplicate rule " + fieldName + " must not be blank");
        }
        return value.trim();
    }
}
