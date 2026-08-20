package net.ximatai.muyun.spring.platform.generation;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionResultBody;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.impact.RecordImpactType;
import net.ximatai.muyun.spring.platform.impact.RecordOriginContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RecordGenerationActionExecutor implements DynamicActionExecutor {
    public static final String EXECUTOR_KEY = "platform.record_generation";

    private final RecordGenerationRuleService ruleService;
    private final FormulaEngine formulaEngine = new FormulaEngine();

    public RecordGenerationActionExecutor(RecordGenerationRuleService ruleService) {
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
    }

    @Override
    public String executorKey() {
        return EXECUTOR_KEY;
    }

    @Override
    public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
        throw new PlatformException("Record generation action requires dynamic action operations");
    }

    @Override
    public Object execute(DynamicActionExecutionContext context,
                          DynamicActionExecutionRequest request,
                          DynamicActionOperations operations) {
        RecordGenerationRule rule = resolveRule(context);
        String sourceRecordId = sourceRecordId(request);
        DynamicRecord source = sourceRecord(context, request, operations, sourceRecordId);
        if (!generationConditionMatches(rule, source)) {
            return DynamicActionResultBody.of(new RecordGenerationResult(
                    rule.getId(),
                    rule.getActionCode(),
                    rule.getSourceModuleAlias(),
                    sourceRecordId,
                    rule.getTargetModuleAlias(),
                    UUID.randomUUID().toString(),
                    List.of()
            ));
        }
        operations.requireAction(rule.getTargetModuleAlias(), PlatformAction.CREATE);
        requireSourceObjectMappings(context, rule);
        String batchId = UUID.randomUUID().toString();
        List<RecordGenerationObjectMapping> mappings = rule.getObjectMappings() == null
                ? List.of()
                : rule.getObjectMappings();
        if (mappings.isEmpty()) {
            throw new PlatformException("Generation rule has no target root object mapping: " + rule.getId());
        }
        RecordGenerationObjectMapping splitDriver = splitDriver(mappings);
        List<RecordGenerationDraft> drafts = splitDriver == null
                ? List.of(draft(rule, mappings, source, operations, sourceRecordId, batchId))
                : splitDrafts(rule, mappings, splitDriver, source, operations, sourceRecordId, batchId);
        return DynamicActionResultBody.of(new RecordGenerationResult(
                rule.getId(),
                rule.getActionCode(),
                rule.getSourceModuleAlias(),
                sourceRecordId,
                rule.getTargetModuleAlias(),
                batchId,
                drafts
        ));
    }

    private RecordGenerationRule resolveRule(DynamicActionExecutionContext context) {
        String moduleAlias = requireText(context == null ? null : context.moduleAlias(), "generation source module");
        String actionCode = requireText(context == null ? null : context.actionCode(), "generation action code");
        return ruleService.selectRuleTreesBySourceModule(moduleAlias).stream()
                .filter(rule -> actionCode.equals(rule.getActionCode()))
                .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("Generation rule not found for action: "
                        + moduleAlias + "." + actionCode));
    }

    private String sourceRecordId(DynamicActionExecutionRequest request) {
        if (request != null && request.recordId() != null && !request.recordId().isBlank()) {
            return request.recordId();
        }
        if (request != null && request.record() != null
                && request.record().getId() != null && !request.record().getId().isBlank()) {
            return request.record().getId();
        }
        throw new PlatformException("Generation action requires source record id");
    }

    private DynamicRecord sourceRecord(DynamicActionExecutionContext context,
                                       DynamicActionExecutionRequest request,
                                       DynamicActionOperations operations,
                                       String sourceRecordId) {
        DynamicRecord selected = operations.select(sourceRecordId);
        if (selected == null) {
            throw new PlatformException("Generation source record not found: "
                    + requireText(context == null ? null : context.moduleAlias(), "generation source module")
                    + "." + sourceRecordId);
        }
        return selected;
    }

    private void requireSourceObjectMappings(DynamicActionExecutionContext context, RecordGenerationRule rule) {
        String sourceEntityAlias = requireText(context == null ? null : context.entityAlias(), "generation source entity");
        List<RecordGenerationObjectMapping> mappings = rule.getObjectMappings() == null
                ? List.of()
                : rule.getObjectMappings();
        if (mappings.isEmpty()) {
            return;
        }
        RecordGenerationObjectMapping rootMapping = mappings.getFirst();
        if (rootMapping.getSourceObjectAlias() != null && !sourceEntityAlias.equals(rootMapping.getSourceObjectAlias())) {
            throw new PlatformException("Generation sourceObjectAlias mismatch: "
                    + rootMapping.getSourceObjectAlias() + " != " + sourceEntityAlias);
        }
    }

    private RecordGenerationDraft draft(RecordGenerationRule rule,
                                        List<RecordGenerationObjectMapping> mappings,
                                        DynamicRecord source,
                                        DynamicActionOperations operations,
                                        String sourceRecordId,
                                        String batchId) {
        RecordGenerationObjectMapping rootMapping = mappings.getFirst();
        DynamicRecord draft = mappedRecord(rule, rootMapping, source, operations);
        for (RecordGenerationObjectMapping childMapping : mappings.stream().skip(1).toList()) {
            String targetRelationCode = relationCode(childMapping.getTargetRelationCode(),
                    childMapping.getTargetObjectAlias(), "targetRelationCode");
            List<DynamicRecord> children = new ArrayList<>();
            for (DynamicRecord sourceChild : sourceChildren(source, childMapping)) {
                children.add(mappedRecord(rule, childMapping, sourceChild, operations));
            }
            draft.setChildren(targetRelationCode, children);
        }
        return recordDraft(rule, rootMapping, draft, sourceRecordId, batchId, 1);
    }

    private List<RecordGenerationDraft> splitDrafts(RecordGenerationRule rule,
                                                    List<RecordGenerationObjectMapping> mappings,
                                                    RecordGenerationObjectMapping splitDriver,
                                                    DynamicRecord source,
                                                    DynamicActionOperations operations,
                                                    String sourceRecordId,
                                                    String batchId) {
        RecordGenerationObjectMapping rootMapping = mappings.getFirst();
        if (splitDriver == rootMapping) {
            throw new PlatformException("Generation split driver must be child object mapping: " + rule.getId());
        }
        if (mappings.size() != 2) {
            throw new PlatformException("Generation split runtime supports root and one split driver mapping only: "
                    + rule.getId());
        }
        if (hasSplitPolicy(splitDriver.getSplitPolicy())) {
            throw new PlatformException("Generation split policy runtime is not supported yet: " + rule.getId());
        }

        List<RecordGenerationDraft> drafts = new ArrayList<>();
        List<DynamicRecord> sourceRows = sourceChildren(source, splitDriver);
        int draftIndex = 1;
        for (DynamicRecord sourceRow : sourceRows) {
            DynamicRecord rootDraft = mappedRecord(rule, rootMapping, source, operations);
            if (Objects.equals(splitDriver.getTargetObjectAlias(), rootMapping.getTargetObjectAlias())) {
                applyFieldMappings(rootDraft, splitDriver, sourceRow);
            } else {
                String targetRelationCode = relationCode(splitDriver.getTargetRelationCode(),
                        splitDriver.getTargetObjectAlias(), "targetRelationCode");
                DynamicRecord childDraft = mappedRecord(rule, splitDriver, sourceRow, operations);
                rootDraft.setChildren(targetRelationCode, List.of(childDraft));
            }
            drafts.add(recordDraft(rule, rootMapping, rootDraft, sourceRecordId, batchId, draftIndex));
            draftIndex++;
        }
        return drafts;
    }

    private DynamicRecord mappedRecord(RecordGenerationRule rule,
                                       RecordGenerationObjectMapping mapping,
                                       DynamicRecord source,
                                       DynamicActionOperations operations) {
        DynamicRecord draft = operations.newRecord(rule.getTargetModuleAlias(), mapping.getTargetObjectAlias());
        applyFieldMappings(draft, mapping, source);
        return draft;
    }

    private void applyFieldMappings(DynamicRecord draft,
                                    RecordGenerationObjectMapping mapping,
                                    DynamicRecord source) {
        for (RecordGenerationFieldMapping fieldMapping : mapping.getFieldMappings()) {
            Object value = mappedValue(fieldMapping, source);
            if (value == null && fieldMapping.getDefaultValue() != null) {
                value = fieldMapping.getDefaultValue();
            }
            if (value != null) {
                draft.setValue(fieldMapping.getTargetField(), generatedFieldValue(draft, fieldMapping.getTargetField(), value));
            }
        }
    }

    /** Formula and constant mappings are adapter values; normalize them before entering DynamicRecord. */
    private Object generatedFieldValue(DynamicRecord draft, String fieldName, Object value) {
        FieldType type = draft.getEntity().fields().stream()
                .filter(field -> field.fieldName().equals(fieldName))
                .map(FieldDefinition::type)
                .findFirst()
                .orElse(null);
        if (type == FieldType.LONG && value instanceof Number number && !(value instanceof Long)) {
            return new BigDecimal(number.toString()).longValueExact();
        }
        if (type == FieldType.DECIMAL && value instanceof Number number && !(value instanceof BigDecimal)) {
            return new BigDecimal(number.toString());
        }
        return value;
    }

    private RecordGenerationDraft recordDraft(RecordGenerationRule rule,
                                              RecordGenerationObjectMapping rootMapping,
                                              DynamicRecord draft,
                                              String sourceRecordId,
                                              String batchId,
                                              int draftIndex) {
        String draftKey = rootMapping.getTargetObjectAlias() + ":" + draftIndex;
        return new RecordGenerationDraft(
                rule.getTargetModuleAlias(),
                rootMapping.getTargetObjectAlias(),
                draft,
                new RecordOriginContext(
                        RecordImpactType.GENERATE_PUSH,
                        rule.getSourceModuleAlias(),
                        sourceRecordId,
                        rule.getTargetModuleAlias(),
                        rule.getId(),
                        rule.getActionCode(),
                        batchId,
                        draftKey
                )
        );
    }

    private RecordGenerationObjectMapping splitDriver(List<RecordGenerationObjectMapping> mappings) {
        List<RecordGenerationObjectMapping> drivers = mappings.stream()
                .filter(mapping -> Boolean.TRUE.equals(mapping.getSplitDriver()))
                .toList();
        if (drivers.size() > 1) {
            throw new PlatformException("Generation rule has multiple split drivers");
        }
        return drivers.isEmpty() ? null : drivers.getFirst();
    }

    private boolean hasSplitPolicy(RecordGenerationSplitPolicy splitPolicy) {
        return splitPolicy != null && (hasText(splitPolicy.getQuantityField())
                || splitPolicy.getQuantityStep() != null
                || splitPolicy.getGroupFields() != null && !splitPolicy.getGroupFields().isEmpty());
    }

    private List<DynamicRecord> sourceChildren(DynamicRecord source, RecordGenerationObjectMapping mapping) {
        String sourceRelationCode = relationCode(mapping.getSourceRelationCode(),
                mapping.getSourceObjectAlias(), "sourceRelationCode");
        List<DynamicRecord> children = source.getChildren(sourceRelationCode);
        if (children == null) {
            throw new PlatformException("Generation source child rows not loaded: " + sourceRelationCode);
        }
        return children;
    }

    private String relationCode(String explicit, String fallback, String fieldName) {
        String value = explicit == null || explicit.isBlank() ? fallback : explicit;
        if (value == null || value.isBlank()) {
            throw new PlatformException("Generation child mapping " + fieldName + " must not be blank");
        }
        return value.trim();
    }

    private Object mappedValue(RecordGenerationFieldMapping mapping, DynamicRecord source) {
        return switch (mapping.getMappingType()) {
            case DIRECT -> source.getValue(mapping.getSourceField());
            case CONSTANT -> mapping.getConstantValue();
            case FORMULA -> formulaEngine.evaluateValue(mapping.getFormulaExpr(),
                    FormulaRuntimeData.of(source.getValues(), Map.of()));
        };
    }

    private boolean generationConditionMatches(RecordGenerationRule rule, DynamicRecord source) {
        String condition = rule.getGenerationCondition();
        if (condition == null || condition.isBlank()) {
            return true;
        }
        return formulaEngine.evaluateBoolean(condition, FormulaRuntimeData.of(source.getValues(), Map.of()));
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(fieldName + " must not be blank");
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
