package net.ximatai.muyun.spring.platform.code;


import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CodeSequenceKeys {
    private final CodePreviewService previewService;
    private final Clock clock;

    public CodeSequenceKeys(CodePreviewService previewService, Clock clock) {
        this.previewService = Objects.requireNonNull(previewService);
        this.clock = Objects.requireNonNull(clock);
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

    private String basisName(CodeRule rule, String segmentId) {
        return rule.getSegments().stream()
                .filter(segment -> Objects.equals(segment.getId(), segmentId))
                .findFirst()
                .map(segment -> hasText(segment.getSourceRef()) ? segment.getSourceRef() : segment.getSegmentType().name())
                .orElse(segmentId == null ? "segment" : segmentId);
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
