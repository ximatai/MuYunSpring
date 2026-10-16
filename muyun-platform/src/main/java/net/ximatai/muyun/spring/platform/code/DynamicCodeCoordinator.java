package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicMutationContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordMutationCoordinator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DynamicCodeCoordinator implements DynamicRecordMutationCoordinator {
    private static final PageRequest ONE = PageRequest.of(1, 1);

    private final CodeRuleService ruleService;
    private final CodeGenerateService generateService;
    private final CodePreviewService previewService;
    private final CodeSequenceStateService sequenceStateService;
    private final CodeLedgerEntryService ledgerEntryService;
    private final CodeRecycleEntryService recycleEntryService;
    private final DynamicRecordService recordService;
    private final CodeBusinessTimeService timeService;
    private final Clock clock;
    private final DynamicCodeContextBuilder contextBuilder;
    private final DynamicCodeDependencyResolver dependencyResolver;
    private final DynamicCodeLedgerRuntimeSupport ledgerRuntimeSupport;

    @Autowired
    public DynamicCodeCoordinator(CodeRuleService ruleService,
                                  CodeGenerateService generateService,
                                  CodePreviewService previewService,
                                  CodeSequenceStateService sequenceStateService,
                                  CodeLedgerEntryService ledgerEntryService,
                                  CodeRecycleEntryService recycleEntryService,
                                  @Lazy DynamicRecordService recordService,
                                  CodeBusinessTimeService timeService,
                                  Clock clock) {
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
        this.generateService = Objects.requireNonNull(generateService, "generateService must not be null");
        this.previewService = previewService == null ? new CodePreviewService() : previewService;
        this.sequenceStateService = sequenceStateService;
        this.ledgerEntryService = ledgerEntryService;
        this.recycleEntryService = recycleEntryService;
        this.recordService = Objects.requireNonNull(recordService, "recordService must not be null");
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.timeService = timeService == null ? new CodeBusinessTimeService(this.clock) : timeService;
        this.contextBuilder = new DynamicCodeContextBuilder();
        this.dependencyResolver = new DynamicCodeDependencyResolver();
        this.ledgerRuntimeSupport = new DynamicCodeLedgerRuntimeSupport(this.previewService, ledgerEntryService,
                recycleEntryService, this.clock);
    }

    @Override
    public void beforeCreate(String moduleAlias, String entityAlias, DynamicRecord record) {
        if (record == null) {
            return;
        }
        String organizationId = resolveOrganizationId(record);
        LocalDateTime at = businessTime(organizationId);
        List<ResolvedCodeRule> resolvedRules = ruleService.resolveRules(new ResolveCodeRuleCommand(
                moduleAlias,
                entityAlias,
                null,
                null,
                organizationId,
                at
        ));
        if (resolvedRules.isEmpty()) {
            return;
        }
        Map<String, Object> context = contextBuilder.recordContext(record);
        for (ResolvedCodeRule resolved : resolvedRules) {
            applyCreateRule(moduleAlias, entityAlias, record, resolved, organizationId, at, context);
        }
    }

    @Override
    public void afterCreate(String moduleAlias, String entityAlias, DynamicRecord record, String id) {
        if (record == null || id == null || id.isBlank()) {
            return;
        }
        record.setId(id);
        syncCurrentBindings(moduleAlias, entityAlias, record, contextBuilder.recordContext(record), resolveOrganizationId(record),
                businessTime(resolveOrganizationId(record)));
    }

    @Override
    public void beforeRelationChildCreate(String moduleAlias,
                                          String parentEntityAlias,
                                          String relationCode,
                                          String childEntityAlias,
                                          DynamicRecord parent,
                                          DynamicRecord child) {
        if (child == null) {
            return;
        }
        String organizationId = resolveOrganizationId(child, parent);
        LocalDateTime at = businessTime(organizationId);
        List<ResolvedCodeRule> resolvedRules = ruleService.resolveRules(new ResolveCodeRuleCommand(
                moduleAlias,
                childEntityAlias,
                null,
                null,
                organizationId,
                at
        ));
        if (resolvedRules.isEmpty()) {
            return;
        }
        Map<String, Object> context = contextBuilder.childRecordContext(parentEntityAlias, relationCode, parent,
                childEntityAlias, child);
        for (ResolvedCodeRule resolved : resolvedRules) {
            applyCreateRule(moduleAlias, childEntityAlias, child, resolved, organizationId, at, context);
        }
    }

    @Override
    public void afterRelationChildCreate(String moduleAlias,
                                         String parentEntityAlias,
                                         String relationCode,
                                         String childEntityAlias,
                                         DynamicRecord parent,
                                         DynamicRecord child,
                                         String id) {
        if (child == null || id == null || id.isBlank()) {
            return;
        }
        child.setId(id);
        String organizationId = resolveOrganizationId(child, parent);
        syncCurrentBindings(moduleAlias, childEntityAlias, child,
                contextBuilder.childRecordContext(parentEntityAlias, relationCode, parent, childEntityAlias, child), organizationId,
                businessTime(organizationId));
    }

    @Override
    public void beforeUpdate(String moduleAlias, String entityAlias, DynamicRecord before, DynamicRecord incoming) {
        if (before == null || incoming == null) {
            return;
        }
        String organizationId = resolveOrganizationId(incoming);
        LocalDateTime at = businessTime(organizationId);
        List<ResolvedCodeRule> resolvedRules = ruleService.resolveRules(new ResolveCodeRuleCommand(
                moduleAlias,
                entityAlias,
                null,
                null,
                organizationId,
                at
        ));
        if (resolvedRules.isEmpty()) {
            return;
        }
        Map<String, Object> context = contextBuilder.mergedRecordContext(before, incoming);
        Set<String> changedKeys = incoming.explicitFieldCodes();
        for (ResolvedCodeRule resolved : resolvedRules) {
            applyUpdateRule(moduleAlias, entityAlias, before, incoming, resolved, organizationId, at, context,
                    contextBuilder.recordContext(before), changedKeys);
        }
    }

    @Override
    public void afterUpdate(String moduleAlias, String entityAlias, DynamicRecord before, DynamicRecord updated) {
        if (before == null || updated == null) {
            return;
        }
        String organizationId = resolveOrganizationId(updated);
        LocalDateTime at = businessTime(organizationId);
        List<ResolvedCodeRule> resolvedRules = ruleService.resolveRules(new ResolveCodeRuleCommand(
                moduleAlias,
                entityAlias,
                null,
                null,
                organizationId,
                at
        ));
        if (resolvedRules.isEmpty()) {
            return;
        }
        Map<String, Object> beforeContext = contextBuilder.recordContext(before);
        Map<String, Object> currentContext = contextBuilder.mergedRecordContext(before, updated);
        for (ResolvedCodeRule resolved : resolvedRules) {
            CodeRule rule = resolved.rule();
            Object oldValue = safeGetValue(before, rule.getFieldName());
            Object newValue = currentContext.get(rule.getFieldName());
            if (sameCode(oldValue, newValue)) {
                syncCurrentBinding(rule, newValue, currentContext, updated.getId(), at);
                continue;
            }
            CodeLedgerAction action = updated.isExplicitlySet(rule.getFieldName())
                    ? CodeLedgerAction.RELEASED_BY_MANUAL_EDIT
                    : CodeLedgerAction.RELEASED_BY_LINKED_UPDATE;
            releaseCode(rule, oldValue, null, beforeContext, before.getId(), action, at);
            syncCurrentBinding(rule, newValue, currentContext, updated.getId(), at);
        }
    }

    @Override
    public void beforeRelationChildUpdate(String moduleAlias,
                                          String parentEntityAlias,
                                          String relationCode,
                                          String childEntityAlias,
                                          DynamicRecord parentBefore,
                                          DynamicRecord parentIncoming,
                                          DynamicRecord childBefore,
                                          DynamicRecord childIncoming) {
        if (childBefore == null || childIncoming == null) {
            return;
        }
        String organizationId = resolveOrganizationId(childIncoming, parentIncoming, parentBefore);
        LocalDateTime at = businessTime(organizationId);
        List<ResolvedCodeRule> resolvedRules = ruleService.resolveRules(new ResolveCodeRuleCommand(
                moduleAlias,
                childEntityAlias,
                null,
                null,
                organizationId,
                at
        ));
        if (resolvedRules.isEmpty()) {
            return;
        }
        Map<String, Object> context = contextBuilder.childMergedRecordContext(parentEntityAlias, relationCode, parentBefore,
                parentIncoming, childEntityAlias, childBefore, childIncoming);
        Set<String> changedKeys = contextBuilder.childChangedKeys(parentEntityAlias, relationCode, parentIncoming,
                childEntityAlias, childIncoming);
        Map<String, Object> beforeContext = contextBuilder.childRecordContext(parentEntityAlias, relationCode, parentBefore,
                childEntityAlias, childBefore);
        for (ResolvedCodeRule resolved : resolvedRules) {
            applyUpdateRule(moduleAlias, childEntityAlias, childBefore, childIncoming, resolved, organizationId, at,
                    context, beforeContext, changedKeys);
        }
    }

    @Override
    public void afterRelationChildUpdate(String moduleAlias,
                                         String parentEntityAlias,
                                         String relationCode,
                                         String childEntityAlias,
                                         DynamicRecord parentBefore,
                                         DynamicRecord parentUpdated,
                                         DynamicRecord childBefore,
                                         DynamicRecord childUpdated) {
        if (childBefore == null || childUpdated == null) {
            return;
        }
        String organizationId = resolveOrganizationId(childUpdated, parentUpdated, parentBefore);
        LocalDateTime at = businessTime(organizationId);
        List<ResolvedCodeRule> resolvedRules = ruleService.resolveRules(new ResolveCodeRuleCommand(
                moduleAlias,
                childEntityAlias,
                null,
                null,
                organizationId,
                at
        ));
        if (resolvedRules.isEmpty()) {
            return;
        }
        Map<String, Object> beforeContext = contextBuilder.childRecordContext(parentEntityAlias, relationCode, parentBefore,
                childEntityAlias, childBefore);
        Map<String, Object> currentContext = contextBuilder.childMergedRecordContext(parentEntityAlias, relationCode, parentBefore,
                parentUpdated, childEntityAlias, childBefore, childUpdated);
        for (ResolvedCodeRule resolved : resolvedRules) {
            CodeRule rule = resolved.rule();
            Object oldValue = safeGetValue(childBefore, rule.getFieldName());
            Object newValue = currentContext.get(rule.getFieldName());
            if (sameCode(oldValue, newValue)) {
                syncCurrentBinding(rule, newValue, currentContext, childUpdated.getId(), at);
                continue;
            }
            CodeLedgerAction action = childUpdated.isExplicitlySet(rule.getFieldName())
                    ? CodeLedgerAction.RELEASED_BY_MANUAL_EDIT
                    : CodeLedgerAction.RELEASED_BY_LINKED_UPDATE;
            releaseCode(rule, oldValue, null, beforeContext, childBefore.getId(), action, at);
            syncCurrentBinding(rule, newValue, currentContext, childUpdated.getId(), at);
        }
    }

    @Override
    public void beforeRelationChildDelete(String moduleAlias,
                                          String parentEntityAlias,
                                          String relationCode,
                                          String childEntityAlias,
                                          DynamicRecord parentBefore,
                                          DynamicRecord childBefore) {
    }

    @Override
    public void afterRelationChildDelete(String moduleAlias,
                                         String parentEntityAlias,
                                         String relationCode,
                                         String childEntityAlias,
                                         DynamicRecord parentBefore,
                                         DynamicRecord childBefore) {
        if (childBefore == null) {
            return;
        }
        String organizationId = resolveOrganizationId(childBefore, parentBefore);
        LocalDateTime at = businessTime(organizationId);
        List<ResolvedCodeRule> resolvedRules = ruleService.resolveRules(new ResolveCodeRuleCommand(
                moduleAlias,
                childEntityAlias,
                null,
                null,
                organizationId,
                at
        ));
        Map<String, Object> beforeContext = contextBuilder.childRecordContext(parentEntityAlias, relationCode, parentBefore,
                childEntityAlias, childBefore);
        for (ResolvedCodeRule resolved : resolvedRules) {
            CodeRule rule = resolved.rule();
            releaseCode(rule, safeGetValue(childBefore, rule.getFieldName()), null, beforeContext, childBefore.getId(),
                    CodeLedgerAction.RELEASED_BY_DELETE, at);
        }
    }

    @Override
    public void afterDelete(String moduleAlias, String entityAlias, DynamicRecord before) {
        if (before == null) {
            return;
        }
        String organizationId = resolveOrganizationId(before);
        LocalDateTime at = businessTime(organizationId);
        List<ResolvedCodeRule> resolvedRules = ruleService.resolveRules(new ResolveCodeRuleCommand(
                moduleAlias,
                entityAlias,
                null,
                null,
                organizationId,
                at
        ));
        Map<String, Object> beforeContext = contextBuilder.recordContext(before);
        for (ResolvedCodeRule resolved : resolvedRules) {
            CodeRule rule = resolved.rule();
            releaseCode(rule, safeGetValue(before, rule.getFieldName()), null, beforeContext, before.getId(),
                    CodeLedgerAction.RELEASED_BY_DELETE, at);
        }
    }

    private void applyCreateRule(String moduleAlias,
                                 String entityAlias,
                                 DynamicRecord record,
                                 ResolvedCodeRule resolved,
                                 String organizationId,
                                 LocalDateTime at,
                                 Map<String, Object> context) {
        CodeRule rule = resolved.rule();
        if (rule.getMode() == CodeMode.MANUAL) {
            return;
        }
        String fieldName = rule.getFieldName();
        Object existingValue = safeGetValue(record, fieldName);
        boolean explicitlySet = record.isExplicitlySet(fieldName);
        if (explicitlySet && existingValue != null && !String.valueOf(existingValue).isBlank()) {
            if (rule.getMode() == CodeMode.AUTO_WITH_MANUAL_EDIT) {
                rejectOccupiedCode(rule, existingValue, null);
                baselineSequenceFromAcceptedValue(rule, existingValue, context, at);
                return;
            }
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-code-coordinator.auto-code-field-does-not-accept-manual-value",
                    "AUTO code field does not accept manual value: {value1}"), Map.of("value1", String.valueOf(fieldName)));
        }
        generateAndAssign(moduleAlias, entityAlias, record, rule, organizationId, at, context, null);
    }

    private void applyUpdateRule(String moduleAlias,
                                 String entityAlias,
                                 DynamicRecord before,
                                 DynamicRecord incoming,
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
        if (explicitlySet && !sameCode(oldValue, incomingValue) && hasText(incomingValue)) {
            if (rule.getMode() != CodeMode.AUTO_WITH_MANUAL_EDIT) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.dynamic-code-coordinator.auto-code-field-does-not-accept-manual-value",
                        "AUTO code field does not accept manual value: {value1}"), Map.of("value1", String.valueOf(fieldName)));
            }
            rejectOccupiedCode(rule, incomingValue, incoming.getId());
            context.put(fieldName, incomingValue);
            baselineSequenceFromAcceptedValue(rule, incomingValue, context, at);
            return;
        }
        if (!hasText(oldValue)) {
            generateAndAssign(moduleAlias, entityAlias, incoming, rule, organizationId, at, context, incoming.getId());
            return;
        }
        if (!Boolean.TRUE.equals(rule.getLinkedUpdate())) {
            return;
        }
        if (dependsOnChangedField(rule, beforeContext, context, changedKeys)) {
            generateAndAssign(moduleAlias, entityAlias, incoming, rule, organizationId, at, context, incoming.getId());
        }
    }

    private void generateAndAssign(String moduleAlias,
                                   String entityAlias,
                                   DynamicRecord record,
                                   CodeRule rule,
                                   String organizationId,
                                   LocalDateTime at,
                                   Map<String, Object> context,
                                   String excludeRecordId) {
        GenerateCodeResult generated = generateService.generate(new GenerateCodeCommand(
                moduleAlias,
                entityAlias,
                rule.getMetadataFieldId(),
                rule.getFieldName(),
                organizationId,
                at,
                context,
                (candidate, generatedValue, candidateContext) -> codeExists(moduleAlias, entityAlias,
                        rule.getFieldName(), generatedValue, excludeRecordId)
        ));
        record.putGeneratedValue(rule.getFieldName(), generated.value());
        context.put(rule.getFieldName(), generated.value());
    }

    private boolean codeExists(String moduleAlias, String entityAlias, String fieldName, String generatedValue,
                               String excludeRecordId) {
        if (generatedValue == null || generatedValue.isBlank()) {
            return false;
        }
        return !recordService.entity(moduleAlias, entityAlias)
                .list(Criteria.of().eq(fieldName, generatedValue), ONE)
                .stream()
                .filter(record -> excludeRecordId == null || !Objects.equals(excludeRecordId, record.getId()))
                .toList()
                .isEmpty();
    }

    private void syncCurrentBindings(String moduleAlias,
                                     String entityAlias,
                                     DynamicRecord record,
                                     Map<String, Object> context,
                                     String organizationId,
                                     LocalDateTime at) {
        if (ledgerEntryService == null) {
            return;
        }
        List<ResolvedCodeRule> resolvedRules = ruleService.resolveRules(new ResolveCodeRuleCommand(
                moduleAlias,
                entityAlias,
                null,
                null,
                organizationId,
                at
        ));
        for (ResolvedCodeRule resolved : resolvedRules) {
            CodeRule rule = resolved.rule();
            syncCurrentBinding(rule, safeGetValue(record, rule.getFieldName()), context, record.getId(), at);
        }
    }

    private void syncCurrentBinding(CodeRule rule,
                                    Object currentValue,
                                    Map<String, Object> context,
                                    String sourceRecordId,
                                    LocalDateTime at) {
        ledgerRuntimeSupport.syncCurrentBinding(rule, currentValue, context, sourceRecordId, at);
    }

    private void rejectOccupiedCode(CodeRule rule, Object codeValue, String sourceRecordId) {
        if (ledgerEntryService == null || rule == null || !hasText(codeValue)) {
            return;
        }
        CodeLedgerEntry entry = ledgerEntryService.findByRuleAndValue(rule.getId(), String.valueOf(codeValue));
        if (entry == null || entry.getStatus() != CodeLedgerStatus.ACTIVE) {
            return;
        }
        if (sourceRecordId == null || sourceRecordId.isBlank()
                || entry.getSourceRecordId() == null || entry.getSourceRecordId().isBlank()
                || !sourceRecordId.equals(entry.getSourceRecordId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-code-coordinator.code-value-is-already-occupied",
                    "Code value is already occupied: {value1}"), Map.of("value1", String.valueOf(codeValue)));
        }
    }

    private void releaseCode(CodeRule rule,
                             Object oldValue,
                             Object newValue,
                             Map<String, Object> previousContext,
                             String sourceRecordId,
                             CodeLedgerAction action,
                             LocalDateTime at) {
        ledgerRuntimeSupport.releaseCode(rule, oldValue, newValue, previousContext, sourceRecordId, action, at);
    }

    private void baselineSequenceFromAcceptedValue(CodeRule rule,
                                                   Object codeValue,
                                                   Map<String, Object> context,
                                                   LocalDateTime at) {
        if (sequenceStateService == null || rule == null || rule.getSequencePolicy() == null || !hasText(codeValue)) {
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

    private Object safeGetValue(DynamicRecord record, String fieldName) {
        try {
            return record.getValue(fieldName);
        } catch (RuntimeException ignored) {
            return null;
        }
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

    private String resolveOrganizationId(DynamicRecord record) {
        return resolveOrganizationId(record, (DynamicRecord[]) null);
    }

    private String resolveOrganizationId(DynamicRecord record, DynamicRecord... fallbackRecords) {
        if (record.getAuthOrganizationId() != null && !record.getAuthOrganizationId().isBlank()) {
            return record.getAuthOrganizationId();
        }
        if (fallbackRecords != null) {
            for (DynamicRecord fallback : fallbackRecords) {
                if (fallback != null && fallback.getAuthOrganizationId() != null
                        && !fallback.getAuthOrganizationId().isBlank()) {
                    return fallback.getAuthOrganizationId();
                }
            }
        }
        return CurrentUserContext.currentUser()
                .map(user -> user.organizationId())
                .orElse(null);
    }

    private LocalDateTime businessTime(String organizationId) {
        Instant instant = DynamicMutationContext.current()
                .map(DynamicMutationContext::startedAt)
                .orElseGet(clock::instant);
        return timeService.resolveBusinessLocalDateTime(organizationId, instant);
    }

}
