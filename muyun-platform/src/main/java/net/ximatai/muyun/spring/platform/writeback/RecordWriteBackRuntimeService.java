package net.ximatai.muyun.spring.platform.writeback;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordMutationEvent;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicWriteBackContext;
import net.ximatai.muyun.spring.platform.impact.RecordImpactRelationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class RecordWriteBackRuntimeService {
    private final List<RecordWriteBackEventListener> listeners;
    private final Optional<RecordWriteBackRuleService> ruleService;
    private final Optional<RecordWriteBackExecutionLogService> executionLogService;
    private final Optional<RecordWriteBackEffectLogService> effectLogService;
    private final Optional<RecordImpactRelationService> impactRelationService;
    private final Optional<DynamicRecordService> dynamicRecordService;

    public RecordWriteBackRuntimeService(List<RecordWriteBackEventListener> listeners) {
        this(listeners, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public RecordWriteBackRuntimeService(List<RecordWriteBackEventListener> listeners,
                                         Optional<RecordWriteBackRuleService> ruleService,
                                         Optional<RecordWriteBackExecutionLogService> executionLogService,
                                         Optional<DynamicRecordService> dynamicRecordService) {
        this(listeners, ruleService, executionLogService, Optional.empty(), Optional.empty(), dynamicRecordService);
    }

    public RecordWriteBackRuntimeService(List<RecordWriteBackEventListener> listeners,
                                         Optional<RecordWriteBackRuleService> ruleService,
                                         Optional<RecordWriteBackExecutionLogService> executionLogService,
                                         Optional<RecordWriteBackEffectLogService> effectLogService,
                                         Optional<DynamicRecordService> dynamicRecordService) {
        this(listeners, ruleService, executionLogService, effectLogService, Optional.empty(), dynamicRecordService);
    }

    @Autowired
    public RecordWriteBackRuntimeService(List<RecordWriteBackEventListener> listeners,
                                         Optional<RecordWriteBackRuleService> ruleService,
                                         Optional<RecordWriteBackExecutionLogService> executionLogService,
                                         Optional<RecordWriteBackEffectLogService> effectLogService,
                                         Optional<RecordImpactRelationService> impactRelationService,
                                         Optional<DynamicRecordService> dynamicRecordService) {
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.ruleService = ruleService == null ? Optional.empty() : ruleService;
        this.executionLogService = executionLogService == null ? Optional.empty() : executionLogService;
        this.effectLogService = effectLogService == null ? Optional.empty() : effectLogService;
        this.impactRelationService = impactRelationService == null ? Optional.empty() : impactRelationService;
        this.dynamicRecordService = dynamicRecordService == null ? Optional.empty() : dynamicRecordService;
    }

    public void onMutationEvent(DynamicRecordMutationEvent event) {
        if (event == null) {
            throw new PlatformException("write-back mutation event must not be null");
        }
        if (event.shouldSkipForSingleHopCascade()) {
            return;
        }
        runConfiguredRules(event);
        for (RecordWriteBackEventListener listener : listeners) {
            listener.onWriteBackEvent(event);
        }
    }

    private void runConfiguredRules(DynamicRecordMutationEvent event) {
        if (ruleService.isEmpty() || dynamicRecordService.isEmpty()) {
            return;
        }
        for (RecordWriteBackRule rule : ruleService.get().selectEnabledRuleTrees(event.moduleAlias(), event.eventType())) {
            Optional<RecordWriteBackTriggerMode> matchedTriggerMode = RecordWriteBackTriggerPolicy.matchedMode(event, rule);
            if (matchedTriggerMode.isEmpty()) {
                continue;
            }
            executeRule(event, rule, matchedTriggerMode.get());
        }
    }

    private void executeRule(DynamicRecordMutationEvent event,
                             RecordWriteBackRule rule,
                             RecordWriteBackTriggerMode triggerMode) {
        RecordWriteBackExecutionLog log = plannedLog(event, rule);
        if (executionLogService.isPresent()) {
            executionLogService.get().insertExecutionLog(log);
        }
        String targetRecordId = null;
        String patchSnapshot = null;
        try {
            TargetRecord target = locateTarget(event, rule);
            targetRecordId = target.effective().getId();
            PatchPlan patchPlan = patchValues(event, rule, triggerMode, target.effective());
            Map<String, Object> patch = patchPlan.values();
            patchSnapshot = patch.toString();
            if (patch.isEmpty()) {
                mark(log, RecordWriteBackExecutionStatus.NOOP, "No write-back field patch", targetRecordId, patchSnapshot);
                return;
            }
            List<RecordWriteBackEffectLog> effects = effectLogs(event, rule, triggerMode, log, target.effective(), patchPlan);
            patch.forEach(target.effective()::setValue);
            DynamicWriteBackContext context = new DynamicWriteBackContext(
                    event.traceId(),
                    event.depth() + 1,
                    log.getId(),
                    rule.getCascadeMode() == RecordWriteBackCascadeMode.CASCADE
            );
            dynamicRecordService.get().updateWriteBack(
                    rule.getTargetModuleAlias(),
                    target.entityAlias(),
                    target.root(),
                    context
            );
            mark(log, RecordWriteBackExecutionStatus.SUCCESS, "Write-back applied", targetRecordId, patchSnapshot);
            reverseEffects(patchPlan.effectsToReverse());
            writeEffects(effects);
        } catch (RuntimeException e) {
            mark(log, RecordWriteBackExecutionStatus.FAILED, e.getMessage(), targetRecordId, patchSnapshot);
            executionLogService.ifPresent(service -> service.saveFailureExecutionLog(log));
            throw e;
        }
    }

    private TargetRecord locateTarget(DynamicRecordMutationEvent event, RecordWriteBackRule rule) {
        DynamicRecord root = locateTargetRoot(event, rule);
        if (!hasText(rule.getTargetRelationCode())) {
            return new TargetRecord(dynamicRecordService.get().mainEntityAlias(rule.getTargetModuleAlias()), root, root);
        }
        return locateTargetChild(event, rule, root);
    }

    private DynamicRecord locateTargetRoot(DynamicRecordMutationEvent event, RecordWriteBackRule rule) {
        if (rule.getTargetLocateMode() == RecordWriteBackTargetLocateMode.GENERATION_RELATION) {
            return locateTargetByGenerationRelation(event, rule);
        }
        return locateTargetByFieldMatch(event, rule);
    }

    private DynamicRecord locateTargetByFieldMatch(DynamicRecordMutationEvent event, RecordWriteBackRule rule) {
        DynamicRecord source = sourceRecord(event);
        Criteria criteria = Criteria.of();
        for (RecordWriteBackMatchRule matchRule : rootMatchRules(rule)) {
            Object sourceValue = source.getValue(matchRule.getSourceField());
            if (sourceValue == null) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-runtime.write-back-source-match-field-is-null",
                        "Write-back source match field is null: {value1}"), Map.of("value1", String.valueOf(matchRule.getSourceField())));
            }
            criteria.eq(matchRule.getTargetField(), sourceValue);
        }
        String targetEntityAlias = dynamicRecordService.get().mainEntityAlias(rule.getTargetModuleAlias());
        List<DynamicRecord> targets = dynamicRecordService.get()
                .listSystem(rule.getTargetModuleAlias(), targetEntityAlias, criteria, PageRequest.of(1, 2));
        if (targets.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-runtime.write-back-target-record-not-found",
                    "Write-back target record not found: {value1}"), Map.of("value1", String.valueOf(rule.getTargetModuleAlias())));
        }
        if (targets.size() > 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-runtime.write-back-target-record-is-not-unique",
                    "Write-back target record is not unique: {value1}"), Map.of("value1", String.valueOf(rule.getTargetModuleAlias())));
        }
        return targets.getFirst();
    }

    private DynamicRecord locateTargetByGenerationRelation(DynamicRecordMutationEvent event,
                                                           RecordWriteBackRule rule) {
        if (impactRelationService.isEmpty()) {
            throw new PlatformException("Record impact relation service is required for generation relation locate");
        }
        List<String> targetRecordIds = impactRelationService.get().findSourceRecordIdsForGeneratedTarget(
                rule.getTargetModuleAlias(),
                event.moduleAlias(),
                event.recordId(),
                rule.getRelationGenerationRuleId()
        );
        if (targetRecordIds.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-runtime.write-back-target-record-not-found-by-generation-relation",
                    "Write-back target record not found by generation relation: {value1}"), Map.of("value1", String.valueOf(rule.getTargetModuleAlias())));
        }
        if (targetRecordIds.size() > 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-runtime.write-back-target-record-is-not-unique-by-generation-relation",
                    "Write-back target record is not unique by generation relation: {value1}"), Map.of("value1", String.valueOf(rule.getTargetModuleAlias())));
        }
        String targetEntityAlias = dynamicRecordService.get().mainEntityAlias(rule.getTargetModuleAlias());
        DynamicRecord target = dynamicRecordService.get()
                .selectSystem(rule.getTargetModuleAlias(), targetEntityAlias, targetRecordIds.getFirst());
        if (target == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-runtime.write-back-target-record-not-found-by-generation-relation",
                    "Write-back target record not found by generation relation: {value1}"), Map.of("value1", String.valueOf(rule.getTargetModuleAlias())));
        }
        return target;
    }

    private TargetRecord locateTargetChild(DynamicRecordMutationEvent event,
                                           RecordWriteBackRule rule,
                                           DynamicRecord root) {
        DynamicRelationDescriptor relation = targetRelation(rule);
        List<RecordWriteBackMatchRule> childMatchRules = childMatchRules(rule);
        Criteria criteria = Criteria.of().eq(relation.childForeignKeyField(), root.getId());
        DynamicRecord source = sourceRecord(event);
        for (RecordWriteBackMatchRule matchRule : childMatchRules) {
            Object sourceValue = source.getValue(matchRule.getSourceField());
            if (sourceValue == null) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-runtime.write-back-source-child-match-field-is-null",
                        "Write-back source child match field is null: {value1}"), Map.of("value1", String.valueOf(matchRule.getSourceField())));
            }
            criteria.eq(matchRule.getTargetField(), sourceValue);
        }
        List<DynamicRecord> children = dynamicRecordService.get().listSystem(
                rule.getTargetModuleAlias(),
                relation.childEntityAlias(),
                criteria,
                PageRequest.of(1, 2)
        );
        if (children.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-runtime.write-back-target-child-row-not-found",
                    "Write-back target child row not found: {value1}.{value2}"), Map.of("value1", String.valueOf(rule.getTargetModuleAlias()), "value2", String.valueOf(rule.getTargetRelationCode())));
        }
        if (children.size() > 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-runtime.write-back-target-child-row-is-not-unique",
                    "Write-back target child row is not unique: {value1}.{value2}"), Map.of("value1", String.valueOf(rule.getTargetModuleAlias()), "value2", String.valueOf(rule.getTargetRelationCode())));
        }
        DynamicRecord child = children.getFirst();
        root.setPartialChildren(relation.code(), List.of(child));
        return new TargetRecord(dynamicRecordService.get().mainEntityAlias(rule.getTargetModuleAlias()), root, child);
    }

    private DynamicRelationDescriptor targetRelation(RecordWriteBackRule rule) {
        String rootEntityAlias = dynamicRecordService.get().mainEntityAlias(rule.getTargetModuleAlias());
        return dynamicRecordService.get().relations(rule.getTargetModuleAlias()).stream()
                .filter(relation -> rootEntityAlias.equals(relation.parentEntityAlias()))
                .filter(relation -> Objects.equals(rule.getTargetRelationCode(), relation.code()))
                .filter(relation -> Objects.equals(rule.getTargetEntityAlias(), relation.childEntityAlias()))
                .findFirst()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-write-back-runtime.write-back-target-child-relation-not-found",
                        "Write-back target child relation not found: {value1}.{value2}"), Map.of("value1", String.valueOf(rule.getTargetModuleAlias()), "value2", String.valueOf(rule.getTargetRelationCode()))));
    }

    private List<RecordWriteBackMatchRule> rootMatchRules(RecordWriteBackRule rule) {
        return rule.getMatchRules().stream()
                .filter(matchRule -> !hasText(matchRule.getTargetRelationCode()))
                .toList();
    }

    private List<RecordWriteBackMatchRule> childMatchRules(RecordWriteBackRule rule) {
        return rule.getMatchRules().stream()
                .filter(matchRule -> Objects.equals(rule.getTargetRelationCode(), matchRule.getTargetRelationCode()))
                .toList();
    }

    private PatchPlan patchValues(DynamicRecordMutationEvent event,
                                  RecordWriteBackRule rule,
                                  RecordWriteBackTriggerMode triggerMode,
                                  DynamicRecord target) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, BigDecimal> contributionValues = new LinkedHashMap<>();
        List<RecordWriteBackEffectLog> effectsToReverse = new ArrayList<>();
        for (RecordWriteBackFieldRule fieldRule : rule.getFieldRules()) {
            if (isContribution(fieldRule)) {
                NumericPatch numericPatch = numericPatchValue(event, rule, triggerMode, target, fieldRule);
                if (numericPatch == null) {
                    continue;
                }
                values.put(fieldRule.getTargetField(), numericPatch.value());
                contributionValues.put(effectKey(fieldRule), numericPatch.contributionValue());
                effectsToReverse.addAll(numericPatch.effectsToReverse());
            } else {
                Object value = sourceValue(sourceRecord(event), fieldRule);
                if (hasAppliedEffect(event, rule, target, fieldRule, value)) {
                    continue;
                }
                values.put(fieldRule.getTargetField(), value);
            }
        }
        return new PatchPlan(values, contributionValues, effectsToReverse);
    }

    private boolean hasAppliedEffect(DynamicRecordMutationEvent event,
                                     RecordWriteBackRule rule,
                                     DynamicRecord target,
                                     RecordWriteBackFieldRule fieldRule,
                                     Object value) {
        if (effectLogService.isEmpty()) {
            return false;
        }
        return !effectLogService.get().selectAppliedEffects(
                rule.getId(),
                event.moduleAlias(),
                event.recordId(),
                rule.getTargetModuleAlias(),
                target.getId(),
                fieldRule.getTargetField(),
                fieldRule.getSourceField(),
                valueText(value)
        ).isEmpty();
    }

    private Object sourceValue(DynamicRecord source, RecordWriteBackFieldRule fieldRule) {
        return switch (fieldRule.getSourceType()) {
            case FIELD -> source.getValue(fieldRule.getSourceField());
            case CONSTANT -> fieldRule.getConstantValue();
        };
    }

    private NumericPatch numericPatchValue(DynamicRecordMutationEvent event,
                                           RecordWriteBackRule rule,
                                           RecordWriteBackTriggerMode triggerMode,
                                           DynamicRecord target,
                                           RecordWriteBackFieldRule fieldRule) {
        if (effectLogService.isEmpty()) {
            throw new PlatformException("Record write-back ADD/SUBTRACT requires effect log service");
        }
        BigDecimal current = numeric(target.getValue(fieldRule.getTargetField()), TextReference.code(
                "platform.rejection.record-write-back-runtime.record-write-back-add-subtract-target-field-must-be-numeric",
                "Record write-back ADD/SUBTRACT target field must be numeric: {value1}"), Map.of("value1", String.valueOf(fieldRule.getTargetField())));
        ActiveContribution active = activeContribution(event, rule, target, fieldRule);
        BigDecimal contributionValue = contributionValue(event, triggerMode, fieldRule);
        BigDecimal delta = numericDelta(triggerMode, fieldRule, active, contributionValue);
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        if (fieldRule.getOperation() == RecordWriteBackFieldOperation.SUBTRACT) {
            delta = delta.negate();
        }
        List<RecordWriteBackEffectLog> effectsToReverse = active.effect() == null
                ? List.of()
                : List.of(active.effect());
        return new NumericPatch(current.add(delta), contributionValue, effectsToReverse);
    }

    private BigDecimal numericDelta(RecordWriteBackTriggerMode triggerMode,
                                    RecordWriteBackFieldRule fieldRule,
                                    ActiveContribution active,
                                    BigDecimal contributionValue) {
        return switch (triggerMode) {
            case ALWAYS, ON_ENTER -> active.exists() ? BigDecimal.ZERO : contributionValue;
            case ON_EXIT -> active.contributionValue().negate();
            case ON_CHANGE_WHILE_EFFECTIVE -> {
                if (!active.exists()) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.record-write-back-runtime.record-write-back-active-contribution-not-found",
                            "Record write-back active contribution not found: {value1}"), Map.of("value1", String.valueOf(fieldRule.getTargetField())));
                }
                yield contributionValue.subtract(active.contributionValue());
            }
        };
    }

    private ActiveContribution activeContribution(DynamicRecordMutationEvent event,
                                                  RecordWriteBackRule rule,
                                                  DynamicRecord target,
                                                  RecordWriteBackFieldRule fieldRule) {
        List<RecordWriteBackEffectLog> activeEffects = effectLogService.get().selectActiveContributions(
                rule.getId(),
                event.moduleAlias(),
                event.recordId(),
                rule.getTargetModuleAlias(),
                target.getId(),
                fieldRule.getTargetField(),
                fieldRule.getSourceField()
        );
        if (activeEffects.size() > 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-runtime.record-write-back-active-contribution-is-not-unique",
                    "Record write-back active contribution is not unique: {value1}.{value2}"), Map.of("value1", String.valueOf(rule.getTargetModuleAlias()), "value2", String.valueOf(fieldRule.getTargetField())));
        }
        if (activeEffects.isEmpty()) {
            return ActiveContribution.empty();
        }
        RecordWriteBackEffectLog effect = activeEffects.getFirst();
        return new ActiveContribution(effect, numeric(effect.getContributionValue(), TextReference.code(
                "platform.rejection.record-write-back-runtime.record-write-back-active-contribution-value-must-be-numeric",
                "Record write-back active contribution value must be numeric"), Map.of()));
    }

    private BigDecimal contributionValue(DynamicRecordMutationEvent event,
                                         RecordWriteBackTriggerMode triggerMode,
                                         RecordWriteBackFieldRule fieldRule) {
        return switch (triggerMode) {
            case ALWAYS, ON_ENTER, ON_CHANGE_WHILE_EFFECTIVE -> numericSourceValue(event.afterRecord(), fieldRule, false);
            case ON_EXIT -> BigDecimal.ZERO;
        };
    }

    private BigDecimal numericSourceValue(DynamicRecord record,
                                          RecordWriteBackFieldRule fieldRule,
                                          boolean before) {
        if (fieldRule.getSourceType() == RecordWriteBackFieldSourceType.CONSTANT) {
            return numeric(fieldRule.getConstantValue(), TextReference.code(
                    "platform.rejection.record-write-back-runtime.record-write-back-add-subtract-constant-value-must-be-numeric",
                    "Record write-back ADD/SUBTRACT constant value must be numeric"), Map.of());
        }
        Object value = recordValue(record, fieldRule.getSourceField());
        return numeric(value, before ? TextReference.code(
                "platform.rejection.record-write-back-runtime.before-source-numeric",
                "Record write-back ADD/SUBTRACT before source field must be numeric: {value1}") : TextReference.code(
                "platform.rejection.record-write-back-runtime.after-source-numeric",
                "Record write-back ADD/SUBTRACT after source field must be numeric: {value1}"), Map.of("value1", String.valueOf(fieldRule.getSourceField())));
    }

    private Object recordValue(DynamicRecord record, String field) {
        if (record == null) {
            return null;
        }
        return record.getValue(field);
    }

    private BigDecimal numeric(Object value, TextReference source, Map<String, Object> args) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                throw PlatformErrors.badRequestText(source, args);
            }
        }
        throw PlatformErrors.badRequestText(source, args);
    }

    private List<RecordWriteBackEffectLog> effectLogs(DynamicRecordMutationEvent event,
                                                      RecordWriteBackRule rule,
                                                      RecordWriteBackTriggerMode triggerMode,
                                                      RecordWriteBackExecutionLog executionLog,
                                                      DynamicRecord target,
                                                      PatchPlan patchPlan) {
        return rule.getFieldRules().stream()
                .filter(fieldRule -> patchPlan.values().containsKey(fieldRule.getTargetField()))
                .map(fieldRule -> {
                    RecordWriteBackEffectLog effectLog = new RecordWriteBackEffectLog();
                    effectLog.setExecutionId(executionLog.getId());
                    effectLog.setTraceId(event.traceId());
                    effectLog.setRuleId(rule.getId());
                    effectLog.setTriggerModuleAlias(event.moduleAlias());
                    effectLog.setTriggerRecordId(event.recordId());
                    effectLog.setTargetModuleAlias(rule.getTargetModuleAlias());
                    effectLog.setTargetRecordId(target.getId());
                    effectLog.setTargetField(fieldRule.getTargetField());
                    effectLog.setSourceType(fieldRule.getSourceType());
                    effectLog.setSourceField(fieldRule.getSourceField());
                    effectLog.setOperation(fieldRule.getOperation());
                    if (isContribution(fieldRule)) {
                        effectLog.setStatus(triggerMode == RecordWriteBackTriggerMode.ON_EXIT
                                ? RecordWriteBackEffectStatus.APPLIED
                                : RecordWriteBackEffectStatus.ACTIVE);
                        effectLog.setContributionValue(valueText(patchPlan.contributionValues().get(effectKey(fieldRule))));
                        effectLog.setDeltaValue(valueText(deltaValue(target, patchPlan.values(), fieldRule)));
                    } else {
                        effectLog.setStatus(RecordWriteBackEffectStatus.APPLIED);
                    }
                    effectLog.setBeforeValue(valueText(target.getValue(fieldRule.getTargetField())));
                    effectLog.setAfterValue(valueText(patchPlan.values().get(fieldRule.getTargetField())));
                    return effectLog;
                })
                .toList();
    }

    private BigDecimal deltaValue(DynamicRecord target,
                                  Map<String, Object> patch,
                                  RecordWriteBackFieldRule fieldRule) {
        return numeric(patch.get(fieldRule.getTargetField()), TextReference.code(
                "platform.rejection.record-write-back-runtime.record-write-back-effect-after-value-must-be-numeric",
                "Record write-back effect after value must be numeric"), Map.of())
                .subtract(numeric(target.getValue(fieldRule.getTargetField()), TextReference.code(
                        "platform.rejection.record-write-back-runtime.record-write-back-effect-before-value-must-be-numeric",
                        "Record write-back effect before value must be numeric"), Map.of()));
    }

    private void writeEffects(List<RecordWriteBackEffectLog> effects) {
        if (effectLogService.isEmpty() || effects == null || effects.isEmpty()) {
            return;
        }
        effects.forEach(effectLogService.get()::insert);
    }

    private void reverseEffects(List<RecordWriteBackEffectLog> effects) {
        if (effectLogService.isEmpty() || effects == null || effects.isEmpty()) {
            return;
        }
        effects.forEach(effect -> {
            effect.setStatus(RecordWriteBackEffectStatus.REVERSED);
            effectLogService.get().update(effect);
        });
    }

    private boolean isContribution(RecordWriteBackFieldRule fieldRule) {
        return fieldRule.getOperation() == RecordWriteBackFieldOperation.ADD
                || fieldRule.getOperation() == RecordWriteBackFieldOperation.SUBTRACT;
    }

    private String effectKey(RecordWriteBackFieldRule fieldRule) {
        return fieldRule.getTargetField() + "\n" + (fieldRule.getSourceField() == null ? "" : fieldRule.getSourceField());
    }

    private DynamicRecord sourceRecord(DynamicRecordMutationEvent event) {
        DynamicRecord source = event.afterRecord() == null ? event.beforeRecord() : event.afterRecord();
        if (source == null) {
            throw new PlatformException("Write-back event has no source record snapshot");
        }
        return source;
    }

    private RecordWriteBackExecutionLog plannedLog(DynamicRecordMutationEvent event, RecordWriteBackRule rule) {
        RecordWriteBackExecutionLog log = new RecordWriteBackExecutionLog();
        log.setTraceId(event.traceId());
        log.setRuleId(rule.getId());
        log.setEventId(event.eventId());
        log.setEventType(event.eventType());
        log.setDepth(event.depth());
        log.setParentExecutionId(event.parentExecutionId());
        log.setTriggerModuleAlias(event.moduleAlias());
        log.setTriggerRecordId(event.recordId());
        log.setTargetModuleAlias(rule.getTargetModuleAlias());
        log.setStatus(RecordWriteBackExecutionStatus.PLANNED);
        log.setEventSnapshot(eventSnapshot(event));
        return log;
    }

    private void mark(RecordWriteBackExecutionLog log,
                      RecordWriteBackExecutionStatus status,
                      String message,
                      String targetRecordId,
                      String patchSnapshot) {
        log.setStatus(status);
        log.setMessage(message);
        if (targetRecordId != null && !targetRecordId.isBlank()) {
            log.setTargetRecordId(targetRecordId);
        }
        if (patchSnapshot != null && !patchSnapshot.isBlank()) {
            log.setPatchSnapshot(patchSnapshot);
        }
        executionLogService.ifPresent(service -> service.updateExecutionLog(log));
    }

    private String valueText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String eventSnapshot(DynamicRecordMutationEvent event) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("eventId", event.eventId());
        snapshot.put("eventType", event.eventType());
        snapshot.put("moduleAlias", event.moduleAlias());
        snapshot.put("entityAlias", event.entityAlias());
        snapshot.put("recordId", event.recordId());
        snapshot.put("mutationSource", event.mutationSource());
        snapshot.put("before", event.beforeRecord() == null ? null : event.beforeRecord().getValues());
        snapshot.put("after", event.afterRecord() == null ? null : event.afterRecord().getValues());
        return snapshot.toString();
    }

    private record PatchPlan(Map<String, Object> values,
                             Map<String, BigDecimal> contributionValues,
                             List<RecordWriteBackEffectLog> effectsToReverse) {
    }

    private record NumericPatch(BigDecimal value,
                                BigDecimal contributionValue,
                                List<RecordWriteBackEffectLog> effectsToReverse) {
    }

    private record ActiveContribution(RecordWriteBackEffectLog effect,
                                      BigDecimal contributionValue) {
        static ActiveContribution empty() {
            return new ActiveContribution(null, BigDecimal.ZERO);
        }

        boolean exists() {
            return effect != null;
        }
    }

    private record TargetRecord(String entityAlias, DynamicRecord root, DynamicRecord effective) {
    }
}
