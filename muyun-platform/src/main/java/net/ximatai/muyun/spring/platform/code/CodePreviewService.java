package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import net.ximatai.muyun.spring.common.option.OptionValueCodeResolver;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class CodePreviewService {
    public static final String SOURCE_VALUE_MISSING = "platform.code.source-value-missing";
    public static final String VALUE_MAPPING_MISSING = "platform.code.value-mapping-missing";
    private final FormulaEngine formulaEngine;
    private final Clock clock;

    @Autowired
    public CodePreviewService() {
        this(new FormulaEngine(), Clock.systemDefaultZone());
    }

    public CodePreviewService(FormulaEngine formulaEngine) {
        this(formulaEngine, Clock.systemDefaultZone());
    }

    public CodePreviewService(FormulaEngine formulaEngine, Clock clock) {
        this.formulaEngine = formulaEngine == null ? new FormulaEngine() : formulaEngine;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public CodePreviewResult previewDraft(PreviewCodeRuleCommand command) {
        if (command == null || command.rule() == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-preview.code-preview-requires-rule-draft",
                    "Code preview requires rule draft"));
        }
        CodeRule rule = command.rule();
        List<CodeRuleSegment> segments = rule.getSegments() == null ? List.of() : rule.getSegments().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CodeRuleSegment::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (segments.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-preview.code-rule-has-no-segments-to-preview",
                    "Code rule has no segments to preview"));
        }
        if (segments.stream().anyMatch(segment -> segment.getSegmentType() == CodeSegmentType.SEQUENCE)
                && rule.getSequencePolicy() == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-preview.code-rule-with-sequence-segment-requires-sequencepolicy",
                    "Code rule with SEQUENCE segment requires sequencePolicy"));
        }

        Map<String, Object> context = command.context() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(command.context());
        // Static models carry enum objects; forms carry option codes. Normalize only enums so
        // every segment, formula and sequence-basis render consumes the same public option value.
        context.replaceAll((source, sourceValue) -> sourceValue instanceof Enum<?> || sourceValue instanceof CodeTitleEnum
                ? OptionValueCodeResolver.resolve(null, sourceValue) : sourceValue);
        LocalDateTime at = command.at() == null ? LocalDateTime.now(clock) : command.at();
        StringBuilder value = new StringBuilder();
        List<CodePreviewSegmentResult> renderedSegments = new ArrayList<>();
        for (CodeRuleSegment segment : segments) {
            String raw = resolveSegmentValue(segment, rule.getSequencePolicy(), context, at, command.sequenceValue());
            if (raw == null && segment.getNullPolicy() == CodeNullPolicy.SKIP_SEGMENT) {
                continue;
            }
            String normalized = normalizeSegmentValue(segment, raw);
            if (normalized != null && !normalized.isEmpty()) {
                value.append(normalized);
            }
            if (segment.getSeparator() != null && !segment.getSeparator().isEmpty()) {
                value.append(segment.getSeparator());
            }
            renderedSegments.add(new CodePreviewSegmentResult(
                    segment.getId(),
                    segment.getSegmentType(),
                    normalized,
                    Boolean.TRUE.equals(segment.getSequenceBasis())
            ));
        }
        return new CodePreviewResult(value.toString(), renderedSegments);
    }

    private String resolveSegmentValue(CodeRuleSegment segment,
                                       CodeSequencePolicy policy,
                                       Map<String, Object> context,
                                       LocalDateTime at,
                                       Long sequenceValue) {
        if (segment.getSegmentType() == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-preview.code-segment-requires-segmenttype",
                    "Code segment requires segmentType"));
        }
        return switch (segment.getSegmentType()) {
            case CONSTANT -> segment.getFixedValue();
            case FIELD_VALUE, CONTEXT_VAR -> readSourceValue(segment, context);
            case SYSTEM_TIME -> formatSystemTime(segment, at);
            case SEQUENCE -> formatSequence(segment, policy, sequenceValue);
            case VALUE_MAPPING -> resolveMappingValue(segment, context);
            case FORMULA -> evaluateFormula(segment, context);
        };
    }

    private String readSourceValue(CodeRuleSegment segment, Map<String, Object> context) {
        Object value = context.get(segment.getSourceRef());
        if (value == null) {
            return handleNullValue(segment);
        }
        if (segment.getDateFormat() != null && value instanceof LocalDateTime localDateTime) {
            return DateTimeFormatter.ofPattern(segment.getDateFormat().pattern()).format(localDateTime);
        }
        return Objects.toString(value, null);
    }

    private String formatSystemTime(CodeRuleSegment segment, LocalDateTime at) {
        CodeDateFormat format = segment.getDateFormat() == null ? CodeDateFormat.YYYYMMDD : segment.getDateFormat();
        return DateTimeFormatter.ofPattern(format.pattern()).format(at);
    }

    private String formatSequence(CodeRuleSegment segment, CodeSequencePolicy policy, Long sequenceValue) {
        if (policy == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-preview.code-sequence-segment-requires-sequencepolicy",
                    "Code sequence segment requires sequencePolicy"));
        }
        long value = sequenceValue == null ? policy.getStartValue() == null ? 1L : policy.getStartValue() : sequenceValue;
        Integer length = segment.getLength() == null ? policy.getSequenceLength() : segment.getLength();
        String text = Long.toString(value);
        if (length != null && length > 0 && text.length() < length) {
            return "0".repeat(length - text.length()) + text;
        }
        return text;
    }

    private String resolveMappingValue(CodeRuleSegment segment, Map<String, Object> context) {
        String sourceValue = readSourceValue(segment, context);
        if (sourceValue == null && segment.getNullPolicy() == CodeNullPolicy.SKIP_SEGMENT) {
            return null;
        }
        List<CodeValueMapping> mappings = segment.getMappings() == null ? List.of() : segment.getMappings();
        return mappings.stream()
                .filter(mapping -> !Boolean.FALSE.equals(mapping.getEnabled()))
                .filter(mapping -> !Boolean.TRUE.equals(mapping.getDefaultMapping()))
                .filter(mapping -> Objects.equals(mapping.getSourceValue(), sourceValue))
                .map(CodeValueMapping::getTargetValue)
                .findFirst()
                .orElseGet(() -> mappings.stream()
                        .filter(mapping -> !Boolean.FALSE.equals(mapping.getEnabled()))
                        .filter(mapping -> Boolean.TRUE.equals(mapping.getDefaultMapping()))
                        .map(CodeValueMapping::getTargetValue)
                        .findFirst()
                        .orElseThrow(() -> new PlatformException(VALUE_MAPPING_MISSING, 400, "Code value mapping not found for source: " + sourceValue)));
    }

    private String evaluateFormula(CodeRuleSegment segment, Map<String, Object> context) {
        if (segment.getFormulaExpr() == null || segment.getFormulaExpr().isBlank()) {
            return handleNullValue(segment);
        }
        Object value = formulaEngine.evaluateValue(segment.getFormulaExpr(), FormulaRuntimeData.of(context));
        return value == null ? handleNullValue(segment) : Objects.toString(value, null);
    }

    private String handleNullValue(CodeRuleSegment segment) {
        CodeNullPolicy policy = segment.getNullPolicy() == null ? CodeNullPolicy.ERROR : segment.getNullPolicy();
        return switch (policy) {
            case SKIP_SEGMENT -> null;
            case USE_DEFAULT -> segment.getFixedValue();
            case ERROR -> throw new PlatformException(SOURCE_VALUE_MISSING, 400, "Code segment source has no value: " + segment.getSourceRef());
        };
    }

    private String normalizeSegmentValue(CodeRuleSegment segment, String value) {
        if (value == null) {
            return null;
        }
        String normalized = truncate(segment, value);
        return pad(segment, normalized);
    }

    private String truncate(CodeRuleSegment segment, String value) {
        if (segment.getLength() == null || segment.getLength() <= 0 || value.length() <= segment.getLength()) {
            return value;
        }
        CodeTruncatePolicy policy = segment.getTruncatePolicy() == null
                ? CodeTruncatePolicy.NONE
                : segment.getTruncatePolicy();
        return switch (policy) {
            case NONE -> value;
            case LEFT -> value.substring(value.length() - segment.getLength());
            case RIGHT -> value.substring(0, segment.getLength());
        };
    }

    private String pad(CodeRuleSegment segment, String value) {
        if (segment.getLength() == null || segment.getLength() <= 0 || value.length() >= segment.getLength()) {
            return value;
        }
        CodePadMode mode = segment.getPadMode() == null ? CodePadMode.NONE : segment.getPadMode();
        if (mode == CodePadMode.NONE) {
            return value;
        }
        String padChar = segment.getPadChar() == null || segment.getPadChar().isEmpty() ? "0" : segment.getPadChar();
        String padding = padChar.repeat(segment.getLength() - value.length());
        return mode == CodePadMode.LEFT ? padding + value : value + padding;
    }
}
