package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.code.StaticCodeFields;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class CodeOpsActionService {
    private final CodeRuleService ruleService;
    private final CodeSequenceStateService sequenceStateService;
    private final CodeIssueLogService issueLogService;
    private final CodeRecycleEntryService recycleEntryService;
    private final CodeLedgerEntryService ledgerEntryService;
    private final DynamicRecordService recordService;

    public CodeOpsActionService(CodeRuleService ruleService,
                                CodeSequenceStateService sequenceStateService,
                                CodeIssueLogService issueLogService,
                                CodeRecycleEntryService recycleEntryService,
                                CodeLedgerEntryService ledgerEntryService,
                                @Lazy DynamicRecordService recordService) {
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
        this.sequenceStateService = Objects.requireNonNull(sequenceStateService, "sequenceStateService must not be null");
        this.issueLogService = Objects.requireNonNull(issueLogService, "issueLogService must not be null");
        this.recycleEntryService = Objects.requireNonNull(recycleEntryService, "recycleEntryService must not be null");
        this.ledgerEntryService = Objects.requireNonNull(ledgerEntryService, "ledgerEntryService must not be null");
        this.recordService = Objects.requireNonNull(recordService, "recordService must not be null");
    }

    @Transactional
    public CodeRecycleEntry adjustRecycleEntry(String entryId, CodeRecycleStatus status, String reason) {
        CodeRecycleEntry existing = recycleEntryService.select(entryId);
        if (existing == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ops-action.code-recycle-entry-does-not-exist",
                    "Code recycle entry does not exist: {value1}"), Map.of("value1", String.valueOf(entryId)));
        }
        CodeRecycleEntry adjusted = recycleEntryService.adjustStatus(entryId, status);
        writeGovernanceLog(existing.getRuleId(), existing.getBasisKey(), existing.getPeriodKey(),
                existing.getRecycledValue(), "Adjusted recycle entry status: " + existing.getStatus() + " -> "
                        + status + reasonSuffix(reason));
        return adjusted;
    }

    @Transactional
    public CodeSequenceBaselineResult setSequenceBaseline(String ruleId,
                                                          String basisKey,
                                                          String periodKey,
                                                          Long currentValue,
                                                          String reason) {
        CodeRule rule = requireRule(ruleId);
        Long beforeValue = null;
        CodeSequenceState before = sequenceStateService.selectState(ruleId, basisKey, periodKey);
        if (before != null) {
            beforeValue = before.getCurrentValue();
        }
        CodeSequenceState state = sequenceStateService.setCurrentValue(ruleId, basisKey, periodKey, currentValue);
        Long nextValue = rule.getSequencePolicy() == null
                ? null
                : sequenceStateService.previewNextValue(state.getCurrentValue(), rule.getSequencePolicy());
        writeGovernanceLog(ruleId, state.getBasisKey(), state.getPeriodKey(), null,
                "Set sequence baseline: " + beforeValue + " -> " + state.getCurrentValue() + reasonSuffix(reason));
        return new CodeSequenceBaselineResult(
                state,
                beforeValue,
                state.getCurrentValue(),
                nextValue,
                "Sequence baseline updated; next value: " + nextValue
        );
    }

    @Transactional
    public CodeSequenceBaselineResult adjustSequenceState(String stateId, Long currentValue, String reason) {
        CodeSequenceState state = sequenceStateService.select(stateId);
        if (state == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ops-action.code-sequence-state-does-not-exist",
                    "Code sequence state does not exist: {value1}"), Map.of("value1", String.valueOf(stateId)));
        }
        return setSequenceBaseline(state.getRuleId(), state.getBasisKey(), state.getPeriodKey(), currentValue, reason);
    }

    public CodeLedgerInspection inspectLedgerEntry(String entryId) {
        CodeLedgerEntry entry = requireActiveLedger(entryId);
        CodeRule rule = requireRule(entry.getRuleId());
        String currentValue = currentBusinessValue(rule, entry);
        CodeLedgerInconsistencyReason reason = inspectReason(entry.getCodeValue(), currentValue);
        boolean consistent = reason == CodeLedgerInconsistencyReason.MATCHED;
        return new CodeLedgerInspection(
                entry,
                currentValue,
                consistent,
                reason,
                !consistent,
                summary(entry.getCodeValue(), currentValue, reason)
        );
    }

    @Transactional
    public CodeLedgerEntry releaseStaleLedgerEntry(String entryId, String reason) {
        CodeLedgerEntry candidate = requireActiveLedger(entryId);
        ledgerEntryService.lockTarget(candidate.getModuleAlias(), candidate.getEntityAlias(), candidate.getFieldName());
        CodeLedgerInspection inspection = inspectLedgerEntry(entryId);
        if (inspection.consistent()) {
            writeGovernanceLog(inspection.entry().getRuleId(), inspection.entry().getBasisKey(),
                    inspection.entry().getPeriodKey(), inspection.entry().getCodeValue(),
                    "Ledger entry is consistent" + reasonSuffix(reason));
            return inspection.entry();
        }
        CodeRule rule = requireRule(inspection.entry().getRuleId());
        CodeLedgerEntry released = ledgerEntryService.releaseStaleBinding(
                inspection.entry(),
                rule,
                CodeLedgerAction.RELEASED_BY_GOVERNANCE
        );
        writeGovernanceLog(rule.getId(), released.getBasisKey(), released.getPeriodKey(), released.getCodeValue(),
                "Released stale ledger entry; current business value: "
                        + normalizeValue(inspection.currentValue()) + reasonSuffix(reason));
        return released;
    }

    private CodeLedgerEntry requireActiveLedger(String entryId) {
        CodeLedgerEntry entry = ledgerEntryService.select(entryId);
        if (entry == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ops-action.code-ledger-entry-does-not-exist",
                    "Code ledger entry does not exist: {value1}"), Map.of("value1", String.valueOf(entryId)));
        }
        if (entry.getStatus() != CodeLedgerStatus.ACTIVE) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ops-action.code-ledger-inspection-only-supports-active-entries",
                    "Code ledger inspection only supports ACTIVE entries"));
        }
        if (entry.getSourceRecordId() == null || entry.getSourceRecordId().isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ops-action.active-code-ledger-entry-requires-sourcerecordid",
                    "ACTIVE code ledger entry requires sourceRecordId"));
        }
        return entry;
    }

    private CodeRule requireRule(String ruleId) {
        CodeRule rule = ruleService.viewRuleTree(ruleId);
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ops-action.code-rule-does-not-exist",
                    "Code rule does not exist: {value1}"), Map.of("value1", String.valueOf(ruleId)));
        }
        return rule;
    }

    private String currentBusinessValue(CodeRule rule, CodeLedgerEntry entry) {
        var staticTarget = ruleService.staticTarget(entry.getModuleAlias(), entry.getEntityAlias());
        if (staticTarget.isPresent()) {
            var owner = staticTarget.get();
            var record = owner instanceof net.ximatai.muyun.spring.ability.SoftDeleteAbility<?> retained
                    ? retained.selectIgnoreSoftDelete(entry.getSourceRecordId()) : owner.selectActiveRaw(entry.getSourceRecordId());
            Object value = record == null ? null : StaticCodeFields.values(record).get(entry.getFieldName());
            return record == null ? null : value == null ? "" : String.valueOf(value);
        }
        DynamicRecord record = recordService.entity(entry.getModuleAlias(), entry.getEntityAlias())
                .selectIgnoreSoftDelete(entry.getSourceRecordId());
        if (record == null) {
            return null;
        }
        Object value = record.getValue(rule.getFieldName());
        return value == null ? "" : String.valueOf(value);
    }

    private CodeLedgerInconsistencyReason inspectReason(String ledgerValue, String currentValue) {
        if (Objects.equals(ledgerValue, currentValue)) {
            return CodeLedgerInconsistencyReason.MATCHED;
        }
        if (currentValue == null) {
            return CodeLedgerInconsistencyReason.RECORD_MISSING;
        }
        if (currentValue.isBlank()) {
            return CodeLedgerInconsistencyReason.VALUE_EMPTY;
        }
        return CodeLedgerInconsistencyReason.VALUE_CHANGED;
    }

    private String summary(String ledgerValue, String currentValue, CodeLedgerInconsistencyReason reason) {
        return switch (reason) {
            case MATCHED -> "Ledger value matches business value";
            case RECORD_MISSING -> "Business record is missing";
            case VALUE_EMPTY -> "Business code value is empty";
            case VALUE_CHANGED -> "Business code value changed";
        } + "; ledger value: " + normalizeValue(ledgerValue) + "; business value: " + normalizeValue(currentValue);
    }

    private void writeGovernanceLog(String ruleId, String basisKey, String periodKey, String value, String message) {
        CodeRule rule = ruleService.viewRuleTree(ruleId);
        issueLogService.write(rule, basisKey, periodKey, value, CodeIssueLogStatus.SUCCESS, 0, message);
    }

    private String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : "; reason: " + reason;
    }

    private String normalizeValue(String value) {
        return value == null || value.isBlank() ? "<empty>" : value;
    }
}
