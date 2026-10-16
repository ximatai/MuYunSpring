package net.ximatai.muyun.spring.platform.writeback;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordMutationEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class RecordWriteBackRuleService extends AbstractAbilityService<RecordWriteBackRule> implements
        SoftDeleteAbility<RecordWriteBackRule>,
        EnableAbility<RecordWriteBackRule>,
        SortAbility<RecordWriteBackRule>,
        QueryAbility<RecordWriteBackRule> {
    public static final String MODULE_ALIAS = "platform.record_write_back_rule";
    private static final PageRequest ALL = PageRequest.of(1, 500);

    private final RecordWriteBackMatchRuleService matchRuleService;
    private final RecordWriteBackFieldRuleService fieldRuleService;

    public RecordWriteBackRuleService(BaseDao<RecordWriteBackRule, String> ruleDao,
                                      RecordWriteBackMatchRuleService matchRuleService,
                                      RecordWriteBackFieldRuleService fieldRuleService) {
        super(MODULE_ALIAS, RecordWriteBackRule.class, ruleDao);
        this.matchRuleService = Objects.requireNonNull(matchRuleService, "matchRuleService must not be null");
        this.fieldRuleService = Objects.requireNonNull(fieldRuleService, "fieldRuleService must not be null");
    }

    @Transactional
    public RecordWriteBackRule saveRuleTree(RecordWriteBackRule rule) {
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-rule-tree-must-not-be-null",
                    "Record write-back rule tree must not be null"));
        }
        List<RecordWriteBackMatchRule> matchRules = rule.getMatchRules() == null
                ? List.of()
                : new ArrayList<>(rule.getMatchRules());
        List<RecordWriteBackFieldRule> fieldRules = rule.getFieldRules() == null
                ? List.of()
                : new ArrayList<>(rule.getFieldRules());
        validateRuleTree(rule, matchRules, fieldRules);
        boolean updating = rule.getId() != null && select(rule.getId()) != null;
        if (updating) {
            update(rule);
        } else {
            insert(rule);
        }
        replaceChildren(rule, matchRules, fieldRules);
        return viewRuleTree(rule.getId());
    }

    public RecordWriteBackRule viewRuleTree(String ruleId) {
        RecordWriteBackRule rule = select(ruleId);
        if (rule == null) {
            return null;
        }
        rule.setMatchRules(matchRuleService.selectByRuleId(ruleId));
        rule.setFieldRules(fieldRuleService.selectByRuleId(ruleId));
        return rule;
    }

    public List<RecordWriteBackRule> selectEnabledRuleTrees(String triggerModuleAlias,
                                                            DynamicRecordMutationEventType eventType) {
        String moduleAlias = PlatformNameRules.requireModuleAlias(triggerModuleAlias);
        if (eventType == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-eventtype-must-not-be-null",
                    "Record write-back eventType must not be null"));
        }
        return list(Criteria.of()
                        .eq("triggerModuleAlias", moduleAlias)
                        .eq("eventType", eventType)
                        .eq("enabled", Boolean.TRUE),
                ALL,
                Sort.asc("sortOrder"))
                .stream()
                .map(rule -> viewRuleTree(rule.getId()))
                .toList();
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, RecordWriteBackRule.class, java.util.List.of("id", "triggerModuleAlias", "targetModuleAlias", "eventType", "cascadeMode", "triggerMode", "targetLocateMode", "targetRelationCode", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(RecordWriteBackRule rule) {
        normalizeAndValidate(rule);
    }

    @Override
    public void beforeUpdate(RecordWriteBackRule rule) {
        normalizeAndValidate(rule);
    }

    private void replaceChildren(RecordWriteBackRule rule,
                                 List<RecordWriteBackMatchRule> matchRules,
                                 List<RecordWriteBackFieldRule> fieldRules) {
        matchRuleService.selectByRuleId(rule.getId()).forEach(matchRuleService::delete);
        fieldRuleService.selectByRuleId(rule.getId()).forEach(fieldRuleService::delete);
        int sort = 1;
        for (RecordWriteBackMatchRule matchRule : matchRules) {
            matchRule.setId(null);
            matchRule.setRuleId(rule.getId());
            if (matchRule.getSortOrder() == null) {
                matchRule.setSortOrder(sort);
            }
            matchRuleService.insert(matchRule);
            sort++;
        }
        sort = 1;
        for (RecordWriteBackFieldRule fieldRule : fieldRules) {
            fieldRule.setId(null);
            fieldRule.setRuleId(rule.getId());
            if (fieldRule.getSortOrder() == null) {
                fieldRule.setSortOrder(sort);
            }
            fieldRuleService.insert(fieldRule);
            sort++;
        }
    }

    private void validateRuleTree(RecordWriteBackRule rule,
                                  List<RecordWriteBackMatchRule> matchRules,
                                  List<RecordWriteBackFieldRule> fieldRules) {
        normalizeAndValidate(rule);
        if (rule.getTargetLocateMode() == RecordWriteBackTargetLocateMode.FIELD_MATCH && matchRules.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-rule-requires-at-least-one-match-rule",
                    "Record write-back rule requires at least one match rule"));
        }
        if (hasText(rule.getTargetRelationCode())) {
            if (matchRules.stream()
                    .noneMatch(matchRule -> Objects.equals(rule.getTargetRelationCode(), matchRule.getTargetRelationCode()))) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-rule.record-write-back-child-target-requires-at-least-one-child-match-rule",
                        "Record write-back child target requires at least one child match rule"));
            }
            if (rule.getTargetLocateMode() == RecordWriteBackTargetLocateMode.FIELD_MATCH
                    && matchRules.stream().noneMatch(matchRule -> !hasText(matchRule.getTargetRelationCode()))) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-rule.record-write-back-child-target-field-match-requires-at-least-one-root",
                        "Record write-back child target field match requires at least one root match rule"));
            }
            if (matchRules.stream().map(RecordWriteBackMatchRule::getTargetRelationCode)
                    .filter(this::hasText)
                    .anyMatch(relationCode -> !Objects.equals(rule.getTargetRelationCode(), relationCode))) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-rule.record-write-back-child-match-relation-must-match-targetrelationcode",
                        "Record write-back child match relation must match targetRelationCode"));
            }
        } else if (matchRules.stream().anyMatch(matchRule -> hasText(matchRule.getTargetRelationCode()))) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-match-rule-targetrelationcode-requires-child-target",
                    "Record write-back match rule targetRelationCode requires child target"));
        }
        if (fieldRules.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-rule-requires-at-least-one-field-rule",
                    "Record write-back rule requires at least one field rule"));
        }
        matchRules.forEach(this::validateDetachedMatchRule);
        fieldRules.forEach(this::validateDetachedFieldRule);
        EnumSet<RecordWriteBackTriggerMode> triggerModes = RecordWriteBackTriggerPolicy.modes(rule);
        if (triggerModes.contains(RecordWriteBackTriggerMode.ALWAYS)
                && fieldRules.stream().anyMatch(this::isNumericContributionRule)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-add-subtract-requires-state-trigger-mode",
                    "Record write-back ADD/SUBTRACT requires state trigger mode"));
        }
    }

    private void normalizeAndValidate(RecordWriteBackRule rule) {
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-rule-must-not-be-null",
                    "Record write-back rule must not be null"));
        }
        rule.setTriggerModuleAlias(PlatformNameRules.requireModuleAlias(rule.getTriggerModuleAlias()));
        rule.setTargetModuleAlias(PlatformNameRules.requireModuleAlias(rule.getTargetModuleAlias()));
        if (rule.getEventType() == null) {
            rule.setEventType(DynamicRecordMutationEventType.AFTER_SAVE);
        }
        if (rule.getEventType() != DynamicRecordMutationEventType.AFTER_SAVE
                && rule.getEventType() != DynamicRecordMutationEventType.AFTER_DELETE) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-only-supports-after-save-and-after-delete",
                    "Record write-back only supports AFTER_SAVE and AFTER_DELETE"));
        }
        if (rule.getCascadeMode() == null) {
            rule.setCascadeMode(RecordWriteBackCascadeMode.SINGLE_HOP);
        }
        if (rule.getTriggerMode() == null) {
            rule.setTriggerMode(RecordWriteBackTriggerMode.ALWAYS);
        }
        if (rule.getTriggerModes() == null || rule.getTriggerModes().isBlank()) {
            rule.setTriggerModes(rule.getTriggerMode().name());
        }
        EnumSet<RecordWriteBackTriggerMode> triggerModes = RecordWriteBackTriggerPolicy.modes(rule);
        rule.setTriggerMode(triggerModes.iterator().next());
        rule.setTriggerModes(RecordWriteBackTriggerPolicy.serialized(triggerModes));
        if (!triggerModes.contains(RecordWriteBackTriggerMode.ALWAYS)) {
            if (rule.getTriggerField() == null || rule.getTriggerField().isBlank()) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-rule.record-write-back-state-trigger-requires-triggerfield",
                        "Record write-back state trigger requires triggerField"));
            }
            if (rule.getTriggerValue() == null || rule.getTriggerValue().isBlank()) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-rule.record-write-back-state-trigger-requires-triggervalue",
                        "Record write-back state trigger requires triggerValue"));
            }
            rule.setTriggerField(rule.getTriggerField().trim());
            rule.setTriggerValue(rule.getTriggerValue().trim());
        }
        if (rule.getTargetLocateMode() == null) {
            rule.setTargetLocateMode(RecordWriteBackTargetLocateMode.FIELD_MATCH);
        }
        if (hasText(rule.getTargetRelationCode())) {
            rule.setTargetRelationCode(rule.getTargetRelationCode().trim());
            if (!hasText(rule.getTargetEntityAlias())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-rule.record-write-back-child-target-requires-targetentityalias",
                        "Record write-back child target requires targetEntityAlias"));
            }
            rule.setTargetEntityAlias(rule.getTargetEntityAlias().trim());
        } else {
            rule.setTargetRelationCode(null);
            if (hasText(rule.getTargetEntityAlias())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-rule.record-write-back-targetentityalias-requires-targetrelationcode",
                        "Record write-back targetEntityAlias requires targetRelationCode"));
            }
            rule.setTargetEntityAlias(null);
        }
        if (rule.getTargetLocateMode() == RecordWriteBackTargetLocateMode.GENERATION_RELATION) {
            if (rule.getRelationGenerationRuleId() == null || rule.getRelationGenerationRuleId().isBlank()) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-rule.record-write-back-generation-relation-locate-requires-relationgenerationruleid",
                        "Record write-back generation relation locate requires relationGenerationRuleId"));
            }
            rule.setRelationGenerationRuleId(rule.getRelationGenerationRuleId().trim());
        }
        if (rule.getTitle() == null || rule.getTitle().isBlank()) {
            rule.setTitle("Write back " + rule.getTargetModuleAlias());
        }
    }

    private void validateDetachedMatchRule(RecordWriteBackMatchRule rule) {
        if (rule.getSourceField() == null || rule.getSourceField().isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-match-rule-sourcefield-must-not-be-blank",
                    "Record write-back match rule sourceField must not be blank"));
        }
        if (rule.getTargetField() == null || rule.getTargetField().isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-match-rule-targetfield-must-not-be-blank",
                    "Record write-back match rule targetField must not be blank"));
        }
        if (hasText(rule.getTargetRelationCode())) {
            rule.setTargetRelationCode(rule.getTargetRelationCode().trim());
        } else {
            rule.setTargetRelationCode(null);
        }
    }

    private void validateDetachedFieldRule(RecordWriteBackFieldRule rule) {
        if (rule.getTargetField() == null || rule.getTargetField().isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-field-rule-targetfield-must-not-be-blank",
                    "Record write-back field rule targetField must not be blank"));
        }
        if (rule.getSourceType() == null) {
            rule.setSourceType(RecordWriteBackFieldSourceType.FIELD);
        }
        if (rule.getOperation() == null) {
            rule.setOperation(RecordWriteBackFieldOperation.COVER);
        }
        if (rule.getSourceType() == RecordWriteBackFieldSourceType.FIELD
                && (rule.getSourceField() == null || rule.getSourceField().isBlank())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-rule.record-write-back-field-rule-sourcefield-must-not-be-blank",
                    "Record write-back field rule sourceField must not be blank"));
        }
        if (rule.getSourceType() == RecordWriteBackFieldSourceType.CONSTANT) {
            rule.setSourceField(null);
        }
    }

    private boolean isNumericContributionRule(RecordWriteBackFieldRule rule) {
        return rule.getOperation() == RecordWriteBackFieldOperation.ADD
                || rule.getOperation() == RecordWriteBackFieldOperation.SUBTRACT;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
