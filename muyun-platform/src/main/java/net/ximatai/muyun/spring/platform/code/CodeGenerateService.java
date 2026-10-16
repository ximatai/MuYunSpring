package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class CodeGenerateService {
    private static final int DEFAULT_DUPLICATE_RETRY = 5;

    private final CodeRuleService ruleService;
    private final CodePreviewService previewService;
    private final CodeSequenceStateService sequenceStateService;
    private final CodeRecycleEntryService recycleEntryService;
    private final CodeIssueLogService issueLogService;
    private final CodeBusinessTimeService timeService;
    private final Clock clock;

    @Autowired
    public CodeGenerateService(CodeRuleService ruleService,
                               CodePreviewService previewService,
                               CodeSequenceStateService sequenceStateService,
                               CodeRecycleEntryService recycleEntryService,
                               CodeIssueLogService issueLogService,
                               CodeBusinessTimeService timeService,
                               Clock clock) {
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
        this.previewService = Objects.requireNonNull(previewService, "previewService must not be null");
        this.sequenceStateService = Objects.requireNonNull(sequenceStateService, "sequenceStateService must not be null");
        this.recycleEntryService = recycleEntryService;
        this.issueLogService = issueLogService;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.timeService = timeService == null ? new CodeBusinessTimeService(this.clock) : timeService;
    }

    public GenerateCodeResult generate(GenerateCodeCommand command) {
        if (command == null) {
            throw new PlatformException("Code generate command must not be null");
        }
        LocalDateTime at = timeService.resolveBusinessLocalDateTime(command.organizationId(), command.at());
        Map<String, Object> context = command.context() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(command.context());
        ResolvedCodeRule resolved = ruleService.resolveRule(new ResolveCodeRuleCommand(
                command.moduleAlias(),
                command.entityAlias(),
                command.moduleMetadataFieldId(),
                command.metadataFieldId(),
                command.fieldName(),
                command.organizationId(),
                at
        ));
        if (resolved == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-generate.no-code-rule-resolved-for-target",
                    "No code rule resolved for target: {value1}/{value2}"), Map.of("value1", String.valueOf(command.moduleAlias()), "value2", String.valueOf(command.entityAlias())));
        }
        return generateResolved(resolved, context, at, command.uniquenessChecker());
    }

    private GenerateCodeResult generateResolved(ResolvedCodeRule resolved,
                                                Map<String, Object> context,
                                                LocalDateTime at,
                                                CodeValueUniquenessChecker uniquenessChecker) {
        CodeRule rule = resolved.rule();
        if (rule.getMode() == CodeMode.MANUAL) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-generate.manual-code-rule-does-not-support-automatic-generation",
                    "MANUAL code rule does not support automatic generation"));
        }
        List<CodeRuleSegment> segments = orderedSegments(rule);
        if (segments.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-generate.code-rule-has-no-segments-to-generate",
                    "Code rule has no segments to generate"));
        }
        boolean hasSequence = segments.stream().anyMatch(segment -> segment.getSegmentType() == CodeSegmentType.SEQUENCE);
        if (hasSequence && rule.getSequencePolicy() == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-generate.code-rule-with-sequence-segment-requires-sequencepolicy",
                    "Code rule with SEQUENCE segment requires sequencePolicy"));
        }
        String basisKey = hasSequence ? buildBasisKey(rule, context, at) : CodeSequenceState.DEFAULT_BUCKET;
        String periodKey = hasSequence ? sequenceStateService.periodKey(rule.getSequencePolicy(), at) : CodeSequenceState.DEFAULT_BUCKET;
        int retry = 0;
        String lastGeneratedValue = null;
        try {
            GenerateCodeResult recycled = tryConsumeRecycle(resolved, context, basisKey, periodKey, at, uniquenessChecker);
            if (recycled != null) {
                writeIssueLog(rule, basisKey, periodKey, recycled.value(), CodeIssueLogStatus.SUCCESS, retry,
                        "Reused recycled code");
                return recycled;
            }

            while (true) {
                Long sequenceValue = hasSequence
                        ? sequenceStateService.allocateNextValue(rule.getId(), basisKey, periodKey, rule.getSequencePolicy())
                        : null;
                CodePreviewResult rendered = previewService.previewDraft(new PreviewCodeRuleCommand(
                        rule,
                        context,
                        resolved.resolvedOrganizationId(),
                        at,
                        sequenceValue
                ));
                lastGeneratedValue = rendered.value();
                if (!isDuplicate(uniquenessChecker, resolved, rendered.value(), context)) {
                    GenerateCodeResult result = new GenerateCodeResult(
                            rendered.value(),
                            rule.getId(),
                            rule.getModuleMetadataFieldId(),
                            rule.getMetadataFieldId(),
                            rule.getFieldName(),
                            rule.getFieldRole(),
                            resolved.resolvedOrganizationId(),
                            basisKey,
                            periodKey,
                            sequenceValue
                    );
                    writeIssueLog(rule, basisKey, periodKey, rendered.value(), CodeIssueLogStatus.SUCCESS, retry,
                            "Generated code");
                    return result;
                }
                if (!hasSequence || retry >= DEFAULT_DUPLICATE_RETRY - 1) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.code-generate.generated-code-already-exists",
                            "Generated code already exists: {value1}"), Map.of("value1", String.valueOf(rendered.value())));
                }
                retry++;
            }
        } catch (RuntimeException ex) {
            writeIssueLog(rule, basisKey, periodKey, lastGeneratedValue, CodeIssueLogStatus.FAILED, retry,
                    ex.getMessage());
            throw ex;
        }
    }

    private GenerateCodeResult tryConsumeRecycle(ResolvedCodeRule resolved,
                                                 Map<String, Object> context,
                                                 String basisKey,
                                                 String periodKey,
                                                 LocalDateTime at,
                                                 CodeValueUniquenessChecker uniquenessChecker) {
        CodeRule rule = resolved.rule();
        if (recycleEntryService == null || !Boolean.TRUE.equals(rule.getAllowRecycle())) {
            return null;
        }
        CodeRecycleEntry entry = recycleEntryService.consumeAvailable(rule.getId(), basisKey, periodKey);
        if (entry == null) {
            return null;
        }
        if (isDuplicate(uniquenessChecker, resolved, entry.getRecycledValue(), context)) {
            entry.setStatus(CodeRecycleStatus.AVAILABLE);
            recycleEntryService.update(entry);
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-generate.generated-code-already-exists",
                    "Generated code already exists: {value1}"), Map.of("value1", String.valueOf(entry.getRecycledValue())));
        }
        return new GenerateCodeResult(
                entry.getRecycledValue(),
                rule.getId(),
                rule.getModuleMetadataFieldId(),
                rule.getMetadataFieldId(),
                rule.getFieldName(),
                rule.getFieldRole(),
                resolved.resolvedOrganizationId(),
                basisKey,
                periodKey,
                null
        );
    }

    private List<CodeRuleSegment> orderedSegments(CodeRule rule) {
        return rule.getSegments() == null ? List.of() : rule.getSegments().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CodeRuleSegment::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    private String buildBasisKey(CodeRule rule, Map<String, Object> context, LocalDateTime at) {
        CodePreviewResult rendered = previewService.previewDraft(new PreviewCodeRuleCommand(
                rule,
                context,
                null,
                at,
                rule.getSequencePolicy() == null ? null : rule.getSequencePolicy().getStartValue()
        ));
        List<String> items = rendered.segments().stream()
                .filter(CodePreviewSegmentResult::sequenceBasis)
                .map(segment -> basisName(rule, segment.segmentId()) + "=" + nullToEmpty(segment.value()))
                .collect(Collectors.toList());
        return items.isEmpty() ? CodeSequenceState.DEFAULT_BUCKET : String.join("||", items);
    }

    private String basisName(CodeRule rule, String segmentId) {
        return orderedSegments(rule).stream()
                .filter(segment -> Objects.equals(segment.getId(), segmentId))
                .findFirst()
                .map(segment -> {
                    if (segment.getSourceRef() != null && !segment.getSourceRef().isBlank()) {
                        return segment.getSourceRef();
                    }
                    if (segment.getSegmentType() != null) {
                        return segment.getSegmentType().name();
                    }
                    return segment.getId();
                })
                .orElse(segmentId == null ? "segment" : segmentId);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isDuplicate(CodeValueUniquenessChecker checker,
                                ResolvedCodeRule resolved,
                                String generatedValue,
                                Map<String, Object> context) {
        return checker != null && checker.exists(resolved, generatedValue, context);
    }

    private void writeIssueLog(CodeRule rule,
                               String basisKey,
                               String periodKey,
                               String generatedValue,
                               CodeIssueLogStatus status,
                               int retryCount,
                               String message) {
        if (issueLogService != null) {
            issueLogService.write(rule, basisKey, periodKey, generatedValue, status, retryCount, message);
        }
    }
}
