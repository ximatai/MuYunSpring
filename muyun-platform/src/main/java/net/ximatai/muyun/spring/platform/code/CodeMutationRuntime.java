package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Shared numbering decisions for static services and dynamic aggregate adapters. */
public final class CodeMutationRuntime {
    private final CodeGenerateService generateService;
    private final CodePreviewService previewService;
    private final CodeSequenceStateService sequenceStateService;
    private final CodeLedgerEntryService ledgerEntryService;
    private final CodeDependencyResolver dependencyResolver = new CodeDependencyResolver();
    private final CodeSequenceKeys ledgerRuntimeSupport;
    private final CodeOccupationService occupations;
    public CodeMutationRuntime(CodeGenerateService generateService, CodePreviewService previewService,
            CodeSequenceStateService sequenceStateService, CodeLedgerEntryService ledgerEntryService,
            CodeRecycleEntryService recycleEntryService, Clock clock) {
        this.generateService = Objects.requireNonNull(generateService);
        this.previewService = Objects.requireNonNull(previewService);
        this.sequenceStateService = Objects.requireNonNull(sequenceStateService);
        this.ledgerEntryService = Objects.requireNonNull(ledgerEntryService);
        this.occupations = new CodeOccupationService(ledgerEntryService, Objects.requireNonNull(recycleEntryService));
        this.ledgerRuntimeSupport = new CodeSequenceKeys(previewService, clock);
    }
    public void prepare(String module, String entity, CodeMutationRecord before, CodeMutationRecord incoming,
            List<ResolvedCodeRule> rules, String organization, LocalDateTime at, Map<String, Object> context,
            Map<String, Object> previousContext, Set<String> changedKeys) {
        changedKeys = new LinkedHashSet<>(changedKeys);
        for (ResolvedCodeRule resolved : dependencyResolver.orderedRules(rules, entity)) {
            CodeRule rule = resolved.rule();
            ledgerEntryService.lockTarget(module, entity, rule.getFieldName());
            Object requested = context.get(rule.getFieldName());
            if (hasText(requested)) {
                String normalized = incoming.normalizeCode(rule.getFieldName(), String.valueOf(requested));
                if (!Objects.equals(normalized, requested)) incoming.putGeneratedValue(rule.getFieldName(), normalized);
                context.put(rule.getFieldName(), normalized);
                if (context.containsKey(entity + "." + rule.getFieldName())) context.put(entity + "." + rule.getFieldName(), normalized);
            }
            Object oldValue = before == null ? null : before.getValue(rule.getFieldName());
            if (rule.getMode() != CodeMode.MANUAL) {
                if (before == null) applyCreateRule(module, entity, incoming, resolved, organization, at, context);
                else applyUpdateRule(module, entity, before, incoming, resolved, organization, at, context, previousContext, changedKeys);
            }
            Object value = context.get(rule.getFieldName());
            if (!sameCode(oldValue, value)) {
                changedKeys.add(rule.getFieldName()); changedKeys.add(entity + "." + rule.getFieldName());
            }
            if (hasText(value)) {
                rejectOccupiedCode(rule, value, incoming.getId());
                if (incoming.codeExists(rule.getFieldName(), String.valueOf(value)))
                    throw new IllegalArgumentException("Business code already exists: " + value);
            }
            if (before != null && !sameCode(oldValue, value))
                occupations.release(module, entity, incoming.getId(), rule.getFieldName(), oldValue == null ? null : String.valueOf(oldValue),
                        incoming.isExplicitlySet(rule.getFieldName()) ? CodeLedgerAction.RELEASED_BY_MANUAL_EDIT : CodeLedgerAction.RELEASED_BY_LINKED_UPDATE);
            if (hasText(value)) {
                var existing = ledgerEntryService.findActiveByTargetAndSourceRecord(module, entity, rule.getFieldName(), incoming.getId());
                if (existing == null || !Objects.equals(existing.getCodeValue(), String.valueOf(value)))
                    ledgerEntryService.upsertActiveBinding(rule, String.valueOf(value), rule.getMode() == CodeMode.MANUAL
                            ? CodeSequenceState.DEFAULT_BUCKET : ledgerRuntimeSupport.basisKey(rule, context, at),
                            ledgerRuntimeSupport.periodKey(rule, at), incoming.getId());
            }
        }
    }
    private boolean alreadyBound(CodeRule rule, String recordId, Object value) {
        if (recordId == null || !hasText(value)) return false;
        var entry = ledgerEntryService.findActiveByTargetAndSourceRecord(rule.getModuleAlias(), rule.getEntityAlias(), rule.getFieldName(), recordId);
        return entry != null && Objects.equals(entry.getCodeValue(), String.valueOf(value));
    }
    private void applyCreateRule(String moduleAlias,
                                 String entityAlias,
                                 CodeMutationRecord record,
                                 ResolvedCodeRule resolved,
                                 String organizationId,
                                 LocalDateTime at,
                                 Map<String, Object> context) {
        CodeRule rule = resolved.rule();
        if (rule.getMode() == CodeMode.MANUAL) {
            return;
        }
        String fieldName = rule.getFieldName();
        if (alreadyBound(rule, record.getId(), safeGetValue(record, fieldName))) return;
        Object existingValue = safeGetValue(record, fieldName);
        boolean explicitlySet = record.isExplicitlySet(fieldName);
        if (explicitlySet && existingValue != null && !String.valueOf(existingValue).isBlank()) {
            if (rule.getMode() == CodeMode.AUTO_WITH_MANUAL_EDIT) {
                rejectOccupiedCode(rule, existingValue, null);
                baselineSequenceFromAcceptedValue(rule, existingValue, context, at);
                return;
            }
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-mutation-runtime.auto-code-field-does-not-accept-manual-value",
                    "AUTO code field does not accept manual value: {value1}"), Map.of("value1", String.valueOf(fieldName)));
        }
        if (record.allowsAutomaticGeneration(fieldName))
            generateAndAssign(moduleAlias, entityAlias, record, rule, organizationId, at, context, null);
    }

    private void applyUpdateRule(String moduleAlias,
                                 String entityAlias,
                                 CodeMutationRecord before,
                                 CodeMutationRecord incoming,
                                 ResolvedCodeRule resolved,
                                 String organizationId,
                                 LocalDateTime at,
                                 Map<String, Object> context,
                                 Map<String, Object> beforeContext,
                                 Set<String> changedKeys) {
        CodeRule rule = resolved.rule();
        if (rule.getMode() == CodeMode.MANUAL) {
            return;
        }
        String fieldName = rule.getFieldName();
        Object oldValue = safeGetValue(before, fieldName);
        Object incomingValue = safeGetValue(incoming, fieldName);
        boolean explicitlySet = incoming.isExplicitlySet(fieldName);
        if (explicitlySet && !sameCode(oldValue, incomingValue) && alreadyBound(rule, incoming.getId(), incomingValue)) return;
        if (explicitlySet && hasText(oldValue) && !hasText(incomingValue))
            throw new IllegalArgumentException("An assigned business code cannot be cleared: " + fieldName);
        if (explicitlySet && !sameCode(oldValue, incomingValue) && hasText(incomingValue)) {
            if (rule.getMode() != CodeMode.AUTO_WITH_MANUAL_EDIT) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.code-mutation-runtime.auto-code-field-does-not-accept-manual-value",
                        "AUTO code field does not accept manual value: {value1}"), Map.of("value1", String.valueOf(fieldName)));
            }
            rejectOccupiedCode(rule, incomingValue, incoming.getId());
            context.put(fieldName, incomingValue);
            baselineSequenceFromAcceptedValue(rule, incomingValue, context, at);
            return;
        }
        if (!hasText(oldValue)) {
            if (!incoming.allowsAutomaticGeneration(fieldName)) return;
            generateAndAssign(moduleAlias, entityAlias, incoming, rule, organizationId, at, context, incoming.getId());
            return;
        }
        if (!incoming.allowsAutomaticGeneration(fieldName) || !Boolean.TRUE.equals(rule.getLinkedUpdate())) {
            return;
        }
        if (dependsOnChangedField(rule, beforeContext, context, changedKeys)) {
            generateAndAssign(moduleAlias, entityAlias, incoming, rule, organizationId, at, context, incoming.getId());
        }
    }

    private void generateAndAssign(String moduleAlias,
                                   String entityAlias,
                                   CodeMutationRecord record,
                                   CodeRule rule,
                                   String organizationId,
                                   LocalDateTime at,
                                   Map<String, Object> context,
                                   String excludeRecordId) {
        GenerateCodeResult generated = generateService.generateResolved(new ResolvedCodeRule(rule, organizationId), context, at,
                (candidate, value, candidateContext) -> record.codeExists(rule.getFieldName(), value)
                        || occupied(rule, value, record.getId()), value -> record.normalizeCode(rule.getFieldName(), value));
        record.putGeneratedValue(rule.getFieldName(), generated.value());
        context.put(rule.getFieldName(), generated.value());
        context.put(entityAlias + "." + rule.getFieldName(), generated.value());
    }

    private boolean occupied(CodeRule rule, String value, String recordId) {
        var entry = ledgerEntryService.findByTargetAndValue(rule.getModuleAlias(), rule.getEntityAlias(), rule.getFieldName(), value);
        return entry != null && (entry.getStatus() == CodeLedgerStatus.DISCARDED
                || entry.getStatus() == CodeLedgerStatus.ACTIVE && !Objects.equals(entry.getSourceRecordId(), recordId));
    }

    private void rejectOccupiedCode(CodeRule rule, Object codeValue, String sourceRecordId) {
        if (rule == null || !hasText(codeValue)) {
            return;
        }
        CodeLedgerEntry entry = ledgerEntryService.findByTargetAndValue(rule.getModuleAlias(), rule.getEntityAlias(), rule.getFieldName(), String.valueOf(codeValue));
        if (entry != null && entry.getStatus() == CodeLedgerStatus.DISCARDED)
            throw PlatformErrors.badRequestText(TextReference.code("platform.code.discarded", "Code value has been discarded: {value1}"), Map.of("value1", codeValue));
        if (entry == null || entry.getStatus() != CodeLedgerStatus.ACTIVE) return;
        if (sourceRecordId == null || sourceRecordId.isBlank()
                || entry.getSourceRecordId() == null || entry.getSourceRecordId().isBlank()
                || !sourceRecordId.equals(entry.getSourceRecordId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-mutation-runtime.code-value-is-already-occupied",
                    "Code value is already occupied: {value1}"), Map.of("value1", String.valueOf(codeValue)));
        }
    }

    private void baselineSequenceFromAcceptedValue(CodeRule rule,
                                                   Object codeValue,
                                                   Map<String, Object> context,
                                                   LocalDateTime at) {
        if (rule == null || rule.getSequencePolicy() == null || !hasText(codeValue)) {
            return;
        }
        Long acceptedSequenceValue = parseAcceptedSequenceValue(rule, String.valueOf(codeValue), context, at);
        if (acceptedSequenceValue == null) {
            return;
        }
        String basisKey = ledgerRuntimeSupport.basisKey(rule, context, at);
        String periodKey = ledgerRuntimeSupport.periodKey(rule, at);
        CodeSequenceState state = sequenceStateService.selectState(rule.getId(), basisKey, periodKey);
        if (state == null || state.getCurrentValue() == null || state.getCurrentValue() < acceptedSequenceValue) {
            sequenceStateService.setCurrentValue(rule.getId(), basisKey, periodKey, acceptedSequenceValue);
        }
    }

    private Long parseAcceptedSequenceValue(CodeRule rule,
                                            String codeValue,
                                            Map<String, Object> context,
                                            LocalDateTime at) {
        List<CodeRuleSegment> segments = rule.getSegments() == null ? List.of() : rule.getSegments();
        if (segments.stream().filter(segment -> segment.getSegmentType() == CodeSegmentType.SEQUENCE).count() != 1) {
            return null;
        }
        CodePreviewResult rendered = previewService.previewDraft(new PreviewCodeRuleCommand(
                rule,
                context == null ? Map.of() : context,
                null,
                at,
                0L
        ));
        List<CodeRuleSegment> sortedSegments = segments.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CodeRuleSegment::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        StringBuilder regex = new StringBuilder("^");
        int renderedIndex = 0;
        for (CodeRuleSegment segment : sortedSegments) {
            if (renderedIndex >= rendered.segments().size()) {
                break;
            }
            CodePreviewSegmentResult renderedSegment = rendered.segments().get(renderedIndex++);
            if (renderedSegment.segmentType() == CodeSegmentType.SEQUENCE) {
                regex.append("(\\d+)");
            } else {
                regex.append(Pattern.quote(renderedSegment.value() == null ? "" : renderedSegment.value()));
            }
            if (segment.getSeparator() != null && !segment.getSeparator().isEmpty()) {
                regex.append(Pattern.quote(segment.getSeparator()));
            }
        }
        regex.append("$");
        java.util.regex.Matcher matcher = Pattern.compile(regex.toString()).matcher(codeValue);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean dependsOnChangedField(CodeRule rule,
                                          Map<String, Object> beforeContext,
                                          Map<String, Object> afterContext,
                                          Set<String> changedKeys) {
        return dependencyResolver.dependsOnChangedField(rule, beforeContext, afterContext, changedKeys);
    }

    private Object safeGetValue(CodeMutationRecord record, String fieldName) {
        return record.getValue(fieldName);
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
