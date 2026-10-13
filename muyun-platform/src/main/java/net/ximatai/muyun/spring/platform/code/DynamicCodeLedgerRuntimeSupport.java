package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DynamicCodeLedgerRuntimeSupport {
    private final CodePreviewService previewService;
    private final CodeLedgerEntryService ledgerEntryService;
    private final CodeRecycleEntryService recycleEntryService;
    private final Clock clock;

    public DynamicCodeLedgerRuntimeSupport(CodePreviewService previewService,
                                           CodeLedgerEntryService ledgerEntryService,
                                           CodeRecycleEntryService recycleEntryService,
                                           Clock clock) {
        this.previewService = previewService == null ? new CodePreviewService() : previewService;
        this.ledgerEntryService = ledgerEntryService;
        this.recycleEntryService = recycleEntryService;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public void syncCurrentBinding(CodeRule rule,
                                   Object currentValue,
                                   Map<String, Object> context,
                                   String sourceRecordId,
                                   LocalDateTime at) {
        if (ledgerEntryService == null || rule == null || rule.getMode() == CodeMode.MANUAL || !hasText(currentValue)) {
            return;
        }
        ledgerEntryService.upsertActiveBinding(
                rule,
                String.valueOf(currentValue),
                basisKey(rule, context, at),
                periodKey(rule, at),
                sourceRecordId
        );
    }

    public void releaseCode(CodeRule rule,
                            Object oldValue,
                            Object newValue,
                            Map<String, Object> previousContext,
                            String sourceRecordId,
                            CodeLedgerAction action,
                            LocalDateTime at) {
        if (rule == null || !hasText(oldValue) || sameCode(oldValue, newValue)) {
            return;
        }
        String basisKey = releaseBasisKey(rule, previousContext, at);
        String periodKey = periodKey(rule, at);
        CodeLedgerStatus inactiveStatus = Boolean.TRUE.equals(rule.getAllowRecycle())
                ? CodeLedgerStatus.AVAILABLE
                : CodeLedgerStatus.DISCARDED;
        if (recycleEntryService != null) {
            recycleEntryService.record(rule, basisKey, periodKey, String.valueOf(oldValue), sourceRecordId);
        }
        if (ledgerEntryService != null) {
            ledgerEntryService.upsertInactiveBinding(rule, String.valueOf(oldValue), basisKey, periodKey, sourceRecordId,
                    inactiveStatus, action);
        }
    }

    public String basisKey(CodeRule rule, Map<String, Object> context, LocalDateTime at) {
        CodePreviewResult rendered = previewService.previewDraft(new PreviewCodeRuleCommand(
                rule,
                context == null ? Map.of() : context,
                null,
                at,
                rule.getSequencePolicy() == null ? null : rule.getSequencePolicy().getStartValue()
        ));
        List<String> items = rendered.segments().stream()
                .filter(CodePreviewSegmentResult::sequenceBasis)
                .map(segment -> basisName(rule, segment.segmentId()) + "="
                        + (segment.value() == null ? "" : segment.value()))
                .toList();
        return items.isEmpty() ? CodeSequenceState.DEFAULT_BUCKET : String.join("||", items);
    }

    public String periodKey(CodeRule rule, LocalDateTime at) {
        if (rule.getSequencePolicy() == null || rule.getSequencePolicy().getResetPolicy() == null) {
            return CodeSequenceState.DEFAULT_BUCKET;
        }
        LocalDateTime effectiveAt = at == null ? LocalDateTime.now(clock) : at;
        return switch (rule.getSequencePolicy().getResetPolicy()) {
            case NONE -> CodeSequenceState.DEFAULT_BUCKET;
            case YEAR -> DateTimeFormatter.ofPattern("yyyy").format(effectiveAt);
            case MONTH -> DateTimeFormatter.ofPattern("yyyyMM").format(effectiveAt);
            case DAY -> DateTimeFormatter.ofPattern("yyyyMMdd").format(effectiveAt);
        };
    }

    private String releaseBasisKey(CodeRule rule, Map<String, Object> context, LocalDateTime at) {
        try {
            return basisKey(rule, context, at);
        } catch (PlatformException ex) {
            if (canFallbackReleaseBasis(ex)) {
                return CodeSequenceState.DEFAULT_BUCKET;
            }
            throw ex;
        }
    }

    private boolean canFallbackReleaseBasis(PlatformException ex) {
        return CodePreviewService.SOURCE_VALUE_MISSING.equals(ex.code())
                || CodePreviewService.VALUE_MAPPING_MISSING.equals(ex.code());
    }

    private String basisName(CodeRule rule, String segmentId) {
        return rule.getSegments().stream()
                .filter(segment -> Objects.equals(segment.getId(), segmentId))
                .findFirst()
                .map(segment -> hasText(segment.getSourceRef()) ? segment.getSourceRef() : segment.getSegmentType().name())
                .orElse(segmentId == null ? "segment" : segmentId);
    }

    private boolean sameCode(Object left, Object right) {
        if (!hasText(left) && !hasText(right)) {
            return true;
        }
        return Objects.equals(left == null ? null : String.valueOf(left), right == null ? null : String.valueOf(right));
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
