package net.ximatai.muyun.spring.platform.duplicate;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-duplicate-rule.duplicate-rule-is-not-enabled-for-action",
                    "duplicate rule is not enabled for action: {value1}"), Map.of("value1", String.valueOf(actionCode)));
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
        rejectDuplicate(rule, scopeCriteria(rule), () -> PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.record-duplicate-rule.duplicate-rule-actioncode-must-be-unique-in-module",
                "duplicate rule actionCode must be unique in module: {value1}.{value2}"), Map.of("value1", String.valueOf(rule.getModuleAlias()), "value2", String.valueOf(rule.getActionCode()))));
    }

    @Override
    public void beforeUpdate(RecordDuplicateRule rule) {
        normalizeAndValidate(rule);
        rejectDuplicate(rule, scopeCriteria(rule), () -> PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.record-duplicate-rule.duplicate-rule-actioncode-must-be-unique-in-module",
                "duplicate rule actionCode must be unique in module: {value1}.{value2}"), Map.of("value1", String.valueOf(rule.getModuleAlias()), "value2", String.valueOf(rule.getActionCode()))));
    }

    private Criteria scopeCriteria(RecordDuplicateRule rule) {
        return Criteria.of()
                .eq("moduleAlias", rule.getModuleAlias())
                .eq("actionCode", rule.getActionCode());
    }

    private void normalizeAndValidate(RecordDuplicateRule rule) {
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-duplicate-rule.duplicate-rule-must-not-be-null",
                    "duplicate rule must not be null"));
        }
        rule.setActionCode(requireActionCode(rule.getActionCode()));
        List<String> fields = normalizeFieldNames(rule.getFieldNames());
        if (fields.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-duplicate-rule.duplicate-rule-requires-fields",
                    "duplicate rule requires fields: {value1}"), Map.of("value1", String.valueOf(rule.getActionCode())));
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
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-duplicate-rule.duplicate-rule-field-duplicated",
                        "duplicate rule field duplicated: {value1}"), Map.of("value1", String.valueOf(fieldName)));
            }
        }
        return List.copyOf(normalized);
    }

    private String requireActionCode(String actionCode) {
        return PlatformNameRules.requireIdentifier(actionCode, "actionCode");
    }

    private String requireFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank() || fieldName.contains(",")) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-duplicate-rule.duplicate-rule-field-name-is-invalid",
                    "duplicate rule field name is invalid: {value1}"), Map.of("value1", String.valueOf(fieldName)));
        }
        return fieldName.trim();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-duplicate-rule.duplicate-rule-must-not-be-blank",
                    "duplicate rule {value1} must not be blank"), Map.of("value1", String.valueOf(fieldName)));
        }
        return value.trim();
    }
}
