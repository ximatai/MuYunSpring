package net.ximatai.muyun.spring.platform.generation;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionResult;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.impact.RecordImpactOriginCoordinator;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ReferenceRecordGenerationFacade {
    private final DynamicRecordService recordService;
    private final RecordGenerationRuleService ruleService;

    public ReferenceRecordGenerationFacade(@Lazy DynamicRecordService recordService,
                                           RecordGenerationRuleService ruleService) {
        this.recordService = Objects.requireNonNull(recordService, "recordService must not be null");
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
    }

    public RecordGenerationResult generateFromReference(String moduleAlias,
                                                        String entityAlias,
                                                        String sourceField,
                                                        String sourceRecordId) {
        DynamicReferenceDescriptor reference = recordService.reference(moduleAlias, entityAlias, sourceField);
        String ruleId = requireText(reference.generateRuleId(), "reference generateRuleId");
        RecordGenerationRule rule = ruleService.viewRuleTree(ruleId);
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.reference-record-generation.reference-generation-rule-is-not-enabled",
                    "Reference generation rule is not enabled: {value1}"), Map.of("value1", String.valueOf(ruleId)));
        }
        requireDirection(reference, moduleAlias, rule);
        DynamicActionExecutionResult result = recordService.executeAction(
                rule.getSourceModuleAlias(),
                rule.getActionCode(),
                DynamicActionExecutionRequest.id(requireText(sourceRecordId, "reference sourceRecordId"))
        );
        if (!(result.value() instanceof RecordGenerationResult generation)) {
            throw new PlatformException("Reference generation action returned unexpected result: " + ruleId);
        }
        return generation;
    }

    public String confirmDraft(RecordGenerationDraft draft) {
        if (draft == null) {
            throw new PlatformException("Record generation draft must not be null");
        }
        if (draft.record() == null) {
            throw new PlatformException("Record generation draft record must not be null");
        }
        if (draft.originContext() == null) {
            throw new PlatformException("Record generation draft requires originContext");
        }
        return recordService.create(
                requireText(draft.targetModuleAlias(), "draft targetModuleAlias"),
                requireText(draft.targetEntityAlias(), "draft targetEntityAlias"),
                draft.record(),
                Map.of(RecordImpactOriginCoordinator.ORIGIN_CONTEXT_KEY, draft.originContext())
        );
    }

    public RecordGenerationCommitResult confirmAll(RecordGenerationResult generation) {
        if (generation == null) {
            throw new PlatformException("Record generation result must not be null");
        }
        List<String> recordIds = new ArrayList<>();
        for (RecordGenerationDraft draft : generation.drafts()) {
            recordIds.add(confirmDraft(draft));
        }
        return new RecordGenerationCommitResult(
                generation.ruleId(),
                generation.batchId(),
                generation.targetModuleAlias(),
                recordIds
        );
    }

    private void requireDirection(DynamicReferenceDescriptor reference,
                                  String moduleAlias,
                                  RecordGenerationRule rule) {
        if (!Objects.equals(rule.getSourceModuleAlias(), reference.targetModuleAlias())
                || !Objects.equals(rule.getTargetModuleAlias(), moduleAlias)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.reference-record-generation.reference-generation-rule-direction-mismatch",
                    "Reference generation rule direction mismatch: {value1}"), Map.of("value1", String.valueOf(rule.getId())));
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.reference-record-generation.reference-generation-requires",
                    "Reference generation requires {value1}"), Map.of("value1", String.valueOf(fieldName)));
        }
        return value.trim();
    }
}
