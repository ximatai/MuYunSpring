package net.ximatai.muyun.spring.platform.generation;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class RecordGenerationRuleService extends AbstractAbilityService<RecordGenerationRule> implements
        SoftDeleteAbility<RecordGenerationRule>,
        EnableAbility<RecordGenerationRule>,
        SortAbility<RecordGenerationRule>,
        QueryAbility<RecordGenerationRule> {
    public static final String MODULE_ALIAS = "platform.record_generation_rule";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final RecordGenerationObjectMappingService objectMappingService;
    private final RecordGenerationFieldMappingService fieldMappingService;
    private final RecordGenerationSplitPolicyService splitPolicyService;
    private final RecordGenerationSplitGroupFieldService splitGroupFieldService;
    private final Optional<GenerationModuleActionContributor> actionContributor;
    private final Optional<ModuleMetadataFieldService> moduleFieldService;

    @Autowired
    public RecordGenerationRuleService(BaseDao<RecordGenerationRule, String> ruleDao,
                                       RecordGenerationObjectMappingService objectMappingService,
                                       RecordGenerationFieldMappingService fieldMappingService,
                                       RecordGenerationSplitPolicyService splitPolicyService,
                                       RecordGenerationSplitGroupFieldService splitGroupFieldService,
                                       Optional<ModuleMetadataFieldService> moduleFieldService,
                                       Optional<GenerationModuleActionContributor> actionContributor) {
        super(MODULE_ALIAS, RecordGenerationRule.class, ruleDao);
        this.objectMappingService = Objects.requireNonNull(objectMappingService, "objectMappingService must not be null");
        this.fieldMappingService = Objects.requireNonNull(fieldMappingService, "fieldMappingService must not be null");
        this.splitPolicyService = Objects.requireNonNull(splitPolicyService, "splitPolicyService must not be null");
        this.splitGroupFieldService = Objects.requireNonNull(splitGroupFieldService, "splitGroupFieldService must not be null");
        this.moduleFieldService = moduleFieldService == null ? Optional.empty() : moduleFieldService;
        this.actionContributor = actionContributor == null ? Optional.empty() : actionContributor;
    }

    public RecordGenerationRuleService(BaseDao<RecordGenerationRule, String> ruleDao,
                                       RecordGenerationObjectMappingService objectMappingService,
                                       RecordGenerationFieldMappingService fieldMappingService,
                                       RecordGenerationSplitPolicyService splitPolicyService,
                                       RecordGenerationSplitGroupFieldService splitGroupFieldService,
                                       Optional<GenerationModuleActionContributor> actionContributor) {
        this(ruleDao, objectMappingService, fieldMappingService, splitPolicyService, splitGroupFieldService,
                Optional.empty(), actionContributor);
    }

    public RecordGenerationRuleService(BaseDao<RecordGenerationRule, String> ruleDao,
                                       RecordGenerationObjectMappingService objectMappingService,
                                       RecordGenerationFieldMappingService fieldMappingService,
                                       RecordGenerationSplitPolicyService splitPolicyService,
                                       RecordGenerationSplitGroupFieldService splitGroupFieldService) {
        this(ruleDao, objectMappingService, fieldMappingService, splitPolicyService, splitGroupFieldService,
                Optional.empty(), Optional.empty());
    }

    @Transactional
    public RecordGenerationRule saveRuleTree(RecordGenerationRule rule) {
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-generation-rule.record-generation-rule-tree-must-not-be-null",
                    "Record generation rule tree must not be null"));
        }
        List<RecordGenerationObjectMapping> objectMappings = rule.getObjectMappings() == null
                ? List.of()
                : new ArrayList<>(rule.getObjectMappings());
        validateRuleTreeSemantics(rule, objectMappings);
        boolean updating = rule.getId() != null && select(rule.getId()) != null;
        if (updating) {
            update(rule);
        } else {
            insert(rule);
        }
        replaceObjectMappings(rule, objectMappings);
        RecordGenerationRule saved = viewRuleTree(rule.getId());
        actionContributor.ifPresent(contributor -> contributor.syncRuleAction(saved));
        return saved;
    }

    public RecordGenerationRule viewRuleTree(String ruleId) {
        RecordGenerationRule rule = select(ruleId);
        if (rule == null) {
            return null;
        }
        List<RecordGenerationObjectMapping> objectMappings = objectMappingService.selectByRuleId(ruleId);
        for (RecordGenerationObjectMapping objectMapping : objectMappings) {
            objectMapping.setFieldMappings(fieldMappingService.selectByObjectMappingId(objectMapping.getId()));
            RecordGenerationSplitPolicy splitPolicy = splitPolicyService.selectByObjectMappingId(objectMapping.getId());
            if (splitPolicy != null) {
                splitPolicy.setGroupFields(splitGroupFieldService.selectBySplitPolicyId(splitPolicy.getId()));
            }
            objectMapping.setSplitPolicy(splitPolicy);
        }
        rule.setObjectMappings(objectMappings);
        return rule;
    }

    public List<RecordGenerationRule> selectRuleTreesBySourceModule(String sourceModuleAlias) {
        String normalizedSourceModuleAlias = PlatformNameRules.requireModuleAlias(sourceModuleAlias);
        return list(Criteria.of().eq("sourceModuleAlias", normalizedSourceModuleAlias), ALL, Sort.asc("sortOrder"))
                .stream()
                .map(rule -> viewRuleTree(rule.getId()))
                .toList();
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, RecordGenerationRule.class, java.util.List.of("id", "sourceModuleAlias", "targetModuleAlias", "actionCode", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(RecordGenerationRule rule) {
        normalizeAndValidate(rule);
    }

    @Override
    public void beforeUpdate(RecordGenerationRule rule) {
        normalizeAndValidate(rule);
    }

    @Override
    public void afterDelete(String id, RecordGenerationRule entity, int deleted) {
        if (deleted > 0) {
            actionContributor.ifPresent(contributor -> contributor.disableRuleAction(id));
        }
    }

    private void replaceObjectMappings(RecordGenerationRule rule, List<RecordGenerationObjectMapping> incoming) {
        for (RecordGenerationObjectMapping existing : objectMappingService.selectByRuleId(rule.getId())) {
            for (RecordGenerationFieldMapping fieldMapping : fieldMappingService.selectByObjectMappingId(existing.getId())) {
                fieldMappingService.delete(fieldMapping);
            }
            deleteSplitPolicy(existing.getId());
            objectMappingService.delete(existing);
        }
        int sort = 1;
        for (RecordGenerationObjectMapping objectMapping : incoming) {
            List<RecordGenerationFieldMapping> fieldMappings = objectMapping.getFieldMappings() == null
                    ? List.of()
                    : new ArrayList<>(objectMapping.getFieldMappings());
            RecordGenerationSplitPolicy splitPolicy = objectMapping.getSplitPolicy();
            objectMapping.setId(null);
            objectMapping.setRuleId(rule.getId());
            if (objectMapping.getSortOrder() == null) {
                objectMapping.setSortOrder(sort);
            }
            objectMappingService.insert(objectMapping);
            int fieldSort = 1;
            for (RecordGenerationFieldMapping fieldMapping : fieldMappings) {
                fieldMapping.setId(null);
                fieldMapping.setObjectMappingId(objectMapping.getId());
                if (fieldMapping.getSortOrder() == null) {
                    fieldMapping.setSortOrder(fieldSort);
                }
                fieldMappingService.insert(fieldMapping);
                fieldSort++;
            }
            replaceSplitPolicy(objectMapping, splitPolicy);
            sort++;
        }
    }

    private void deleteSplitPolicy(String objectMappingId) {
        RecordGenerationSplitPolicy existing = splitPolicyService.selectByObjectMappingId(objectMappingId);
        if (existing != null) {
            for (RecordGenerationSplitGroupField groupField : splitGroupFieldService.selectBySplitPolicyId(existing.getId())) {
                splitGroupFieldService.delete(groupField);
            }
            splitPolicyService.delete(existing);
        }
    }

    private void replaceSplitPolicy(RecordGenerationObjectMapping objectMapping, RecordGenerationSplitPolicy incoming) {
        if (incoming == null) {
            return;
        }
        List<RecordGenerationSplitGroupField> groupFields = incoming.getGroupFields() == null
                ? List.of()
                : new ArrayList<>(incoming.getGroupFields());
        incoming.setId(null);
        incoming.setObjectMappingId(objectMapping.getId());
        splitPolicyService.insert(incoming);
        int sort = 1;
        for (RecordGenerationSplitGroupField groupField : groupFields) {
            groupField.setId(null);
            groupField.setSplitPolicyId(incoming.getId());
            if (groupField.getSortOrder() == null) {
                groupField.setSortOrder(sort);
            }
            splitGroupFieldService.insert(groupField);
            sort++;
        }
    }

    private void normalizeAndValidate(RecordGenerationRule rule) {
        rule.setSourceModuleAlias(PlatformNameRules.requireModuleAlias(rule.getSourceModuleAlias()));
        rule.setTargetModuleAlias(PlatformNameRules.requireModuleAlias(rule.getTargetModuleAlias()));
        if (rule.getActionCode() == null || rule.getActionCode().isBlank()) {
            rule.setActionCode(defaultActionCode(rule.getTargetModuleAlias()));
        }
        rule.setActionCode(PlatformNameRules.requireActionCode(rule.getActionCode(), "actionCode"));
        if (rule.getGenerationCondition() != null && rule.getGenerationCondition().isBlank()) {
            rule.setGenerationCondition(null);
        }
        if (rule.getTitle() == null || rule.getTitle().isBlank()) {
            rule.setTitle("Generate " + rule.getTargetModuleAlias());
        }
    }

    private void validateRuleTreeSemantics(RecordGenerationRule rule,
                                           List<RecordGenerationObjectMapping> objectMappings) {
        normalizeAndValidate(rule);
        validateUniqueAction(rule);
        int splitDrivers = 0;
        for (RecordGenerationObjectMapping objectMapping : objectMappings) {
            validateFieldMappingTargets(rule, objectMapping);
            if (Boolean.TRUE.equals(objectMapping.getSplitDriver())) {
                splitDrivers++;
            }
            if (objectMapping.getSplitPolicy() != null && !Boolean.TRUE.equals(objectMapping.getSplitDriver())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-generation-rule.generation-split-policy-requires-splitdriver-object-mapping",
                        "Generation split policy requires splitDriver object mapping"));
            }
            validateSplitPolicy(rule, objectMapping, objectMapping.getSplitPolicy());
        }
        if (splitDrivers > 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-generation-rule.generation-rule-can-only-declare-one-split-driver-object-mapping",
                    "Generation rule can only declare one split driver object mapping"));
        }
    }

    private void validateUniqueAction(RecordGenerationRule rule) {
        List<RecordGenerationRule> existingRules = list(Criteria.of()
                .eq("sourceModuleAlias", rule.getSourceModuleAlias())
                .eq("actionCode", rule.getActionCode()), ALL, Sort.asc("sortOrder"));
        for (RecordGenerationRule existing : existingRules) {
            if (!Objects.equals(existing.getId(), rule.getId())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-generation-rule.generation-rule-actioncode-must-be-unique-within-source-module",
                        "Generation rule actionCode must be unique within source module: {value1}.{value2}"), Map.of("value1", String.valueOf(rule.getSourceModuleAlias()), "value2", String.valueOf(rule.getActionCode())));
            }
        }
    }

    private void validateFieldMappingTargets(RecordGenerationRule rule, RecordGenerationObjectMapping objectMapping) {
        List<RecordGenerationFieldMapping> fieldMappings = objectMapping.getFieldMappings() == null
                ? List.of()
                : objectMapping.getFieldMappings();
        Set<String> targetFields = new LinkedHashSet<>();
        for (RecordGenerationFieldMapping fieldMapping : fieldMappings) {
            resolveFieldMappingFields(rule, objectMapping, fieldMapping);
            String targetField = targetFieldKey(fieldMapping);
            if (!targetFields.add(targetField)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-generation-rule.field-mappings-cannot-duplicate-targetfield-in-one-object-mapping",
                        "Field mappings cannot duplicate targetField in one object mapping: {value1}"), Map.of("value1", String.valueOf(targetField)));
            }
        }
    }

    private void resolveFieldMappingFields(RecordGenerationRule rule,
                                           RecordGenerationObjectMapping objectMapping,
                                           RecordGenerationFieldMapping fieldMapping) {
        if (fieldMapping.getSourceModuleMetadataFieldId() != null
                && !fieldMapping.getSourceModuleMetadataFieldId().isBlank()) {
            ResolvedModuleMetadataField source = resolveModuleField(fieldMapping.getSourceModuleMetadataFieldId(),
                    rule.getSourceModuleAlias(), sourceObjectAlias(objectMapping), "sourceModuleMetadataFieldId");
            if (source != null) {
                fieldMapping.setSourceField(source.fieldName());
            }
        }
        if (fieldMapping.getTargetModuleMetadataFieldId() != null
                && !fieldMapping.getTargetModuleMetadataFieldId().isBlank()) {
            ResolvedModuleMetadataField target = resolveModuleField(fieldMapping.getTargetModuleMetadataFieldId(),
                    rule.getTargetModuleAlias(), objectMapping.getTargetObjectAlias(), "targetModuleMetadataFieldId");
            if (target != null) {
                fieldMapping.setTargetField(target.fieldName());
            }
        }
        fieldMapping.setTargetField(PlatformNameRules.requireFieldName(fieldMapping.getTargetField(), "targetField"));
    }

    private String targetFieldKey(RecordGenerationFieldMapping fieldMapping) {
        if (fieldMapping.getTargetModuleMetadataFieldId() != null
                && !fieldMapping.getTargetModuleMetadataFieldId().isBlank()) {
            return fieldMapping.getTargetModuleMetadataFieldId();
        }
        return PlatformNameRules.requireFieldName(fieldMapping.getTargetField(), "targetField");
    }

    private void validateSplitPolicy(RecordGenerationRule rule,
                                     RecordGenerationObjectMapping objectMapping,
                                     RecordGenerationSplitPolicy splitPolicy) {
        if (splitPolicy == null) {
            return;
        }
        resolveSplitPolicyFields(rule, objectMapping, splitPolicy);
        List<RecordGenerationSplitGroupField> groupFields = splitPolicy.getGroupFields() == null
                ? List.of()
                : splitPolicy.getGroupFields();
        boolean quantityDriver = splitPolicy.getQuantityField() != null && !splitPolicy.getQuantityField().isBlank();
        if (!quantityDriver && splitPolicy.getQuantityStep() != null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-generation-rule.split-policy-quantitystep-requires-quantityfield",
                    "Split policy quantityStep requires quantityField"));
        }
        Set<String> groupFieldNames = new LinkedHashSet<>();
        for (RecordGenerationSplitGroupField groupField : groupFields) {
            if (groupField.getModuleMetadataFieldId() != null && !groupField.getModuleMetadataFieldId().isBlank()) {
                ResolvedModuleMetadataField resolved = resolveModuleField(groupField.getModuleMetadataFieldId(),
                        rule.getSourceModuleAlias(), sourceObjectAlias(objectMapping), "moduleMetadataFieldId");
                if (resolved != null) {
                    groupField.setFieldName(resolved.fieldName());
                }
            }
            String fieldKey = groupField.getModuleMetadataFieldId() == null || groupField.getModuleMetadataFieldId().isBlank()
                    ? PlatformNameRules.requireFieldName(groupField.getFieldName(), "fieldName")
                    : groupField.getModuleMetadataFieldId();
            if (!groupFieldNames.add(fieldKey)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-generation-rule.split-policy-cannot-duplicate-group-field",
                        "Split policy cannot duplicate group field: {value1}"), Map.of("value1", String.valueOf(fieldKey)));
            }
        }
        if (quantityDriver && splitPolicy.getQuantityStep() == null) {
            splitPolicy.setQuantityStep(1);
        }
    }

    private void resolveSplitPolicyFields(RecordGenerationRule rule,
                                          RecordGenerationObjectMapping objectMapping,
                                          RecordGenerationSplitPolicy splitPolicy) {
        if (splitPolicy.getQuantityModuleMetadataFieldId() == null
                || splitPolicy.getQuantityModuleMetadataFieldId().isBlank()) {
            return;
        }
        ResolvedModuleMetadataField resolved = resolveModuleField(splitPolicy.getQuantityModuleMetadataFieldId(),
                rule.getSourceModuleAlias(), sourceObjectAlias(objectMapping), "quantityModuleMetadataFieldId");
        if (resolved != null) {
            splitPolicy.setQuantityField(resolved.fieldName());
        }
    }

    private ResolvedModuleMetadataField resolveModuleField(String moduleMetadataFieldId,
                                                          String expectedModuleAlias,
                                                          String expectedObjectAlias,
                                                          String label) {
        if (moduleFieldService.isEmpty()) {
            throw new PlatformException("Generation " + label + " requires ModuleMetadataFieldService");
        }
        ResolvedModuleMetadataField resolved = moduleFieldService.get().resolve(moduleMetadataFieldId);
        if (!Objects.equals(resolved.moduleAlias(), expectedModuleAlias)
                || !Objects.equals(resolved.relationAlias(), expectedObjectAlias)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-generation-rule.generation-does-not-belong-to-expected-object",
                    "Generation {value1} does not belong to expected object: {value2}/{value3}"), Map.of("value1", String.valueOf(label), "value2", String.valueOf(expectedModuleAlias), "value3", String.valueOf(expectedObjectAlias)));
        }
        return resolved;
    }

    private String sourceObjectAlias(RecordGenerationObjectMapping objectMapping) {
        return objectMapping.getSourceObjectAlias() == null || objectMapping.getSourceObjectAlias().isBlank()
                ? objectMapping.getTargetObjectAlias()
                : objectMapping.getSourceObjectAlias();
    }

    private String defaultActionCode(String targetModuleAlias) {
        String lastSegment = targetModuleAlias.substring(targetModuleAlias.lastIndexOf('.') + 1);
        StringBuilder builder = new StringBuilder("generate");
        boolean upperNext = true;
        for (char ch : lastSegment.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return builder.toString();
    }
}
