package net.ximatai.muyun.spring.platform.code;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class CodeOpsQueryService {
    private static final int DEFAULT_LIMIT = 20;

    private final CodeRuleService ruleService;
    private final CodeSequenceStateService sequenceStateService;
    private final CodeIssueLogService issueLogService;
    private final CodeRecycleEntryService recycleEntryService;
    private final CodeLedgerEntryService ledgerEntryService;

    public CodeOpsQueryService(CodeRuleService ruleService,
                               CodeSequenceStateService sequenceStateService,
                               CodeIssueLogService issueLogService,
                               CodeRecycleEntryService recycleEntryService,
                               CodeLedgerEntryService ledgerEntryService) {
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
        this.sequenceStateService = Objects.requireNonNull(sequenceStateService, "sequenceStateService must not be null");
        this.issueLogService = Objects.requireNonNull(issueLogService, "issueLogService must not be null");
        this.recycleEntryService = Objects.requireNonNull(recycleEntryService, "recycleEntryService must not be null");
        this.ledgerEntryService = Objects.requireNonNull(ledgerEntryService, "ledgerEntryService must not be null");
    }

    public CodeRuleOpsSnapshot viewRuleSnapshot(String ruleId, Integer limitPerCategory) {
        CodeRule rule = ruleService.viewRuleTree(ruleId);
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ops-query.code-rule-does-not-exist",
                    "Code rule does not exist: {value1}"), Map.of("value1", String.valueOf(ruleId)));
        }
        return snapshot(rule, normalizeLimit(limitPerCategory));
    }

    public List<CodeRuleOpsSnapshot> queryBusinessObjectSnapshots(String moduleAlias,
                                                                  String entityAlias,
                                                                  Integer limitPerCategory) {
        int limit = normalizeLimit(limitPerCategory);
        return ruleService.selectRuleTreesByBusinessObject(moduleAlias, entityAlias)
                .stream()
                .map(rule -> snapshot(rule, limit))
                .toList();
    }

    public CodeSequenceStateLocation locateSequenceState(String ruleId, String basisKey, String periodKey) {
        CodeRule rule = requireRule(ruleId);
        String effectiveBasisKey = normalizeBucket(basisKey);
        String effectivePeriodKey = normalizeBucket(periodKey);
        CodeSequenceState state = sequenceStateService.selectState(ruleId, effectiveBasisKey, effectivePeriodKey);
        Long nextValue = rule.getSequencePolicy() == null
                ? null
                : sequenceStateService.previewNextValue(state == null ? null : state.getCurrentValue(), rule.getSequencePolicy());
        return new CodeSequenceStateLocation(
                ruleId,
                effectiveBasisKey,
                effectivePeriodKey,
                state != null,
                state,
                nextValue,
                state == null ? "Sequence state does not exist; baseline can create it"
                        : "Sequence state located"
        );
    }

    private CodeRuleOpsSnapshot snapshot(CodeRule rule, int limit) {
        return new CodeRuleOpsSnapshot(
                rule,
                sequenceStateService.selectByRuleId(rule.getId(), limit),
                issueLogService.selectByRuleId(rule.getId(), limit),
                recycleEntryService.selectByRuleId(rule.getId(), limit),
                ledgerEntryService.selectByRuleId(rule.getId(), limit)
        );
    }

    private CodeRule requireRule(String ruleId) {
        CodeRule rule = ruleService.viewRuleTree(ruleId);
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ops-query.code-rule-does-not-exist",
                    "Code rule does not exist: {value1}"), Map.of("value1", String.valueOf(ruleId)));
        }
        return rule;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, 100);
    }

    private String normalizeBucket(String value) {
        return value == null || value.isBlank() ? CodeSequenceState.DEFAULT_BUCKET : value;
    }
}
