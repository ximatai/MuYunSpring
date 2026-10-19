package net.ximatai.muyun.spring.platform.code;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.OrganizationHierarchyService;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
@DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE})
public class CodeRuleService extends AbstractAbilityService<CodeRule> implements
        SoftDeleteAbility<CodeRule>,
        EnableAbility<CodeRule>,
        SortAbility<CodeRule>,
        QueryAbility<CodeRule> {
    public static final String MODULE_ALIAS = "platform.code_rule";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final CodeRuleSegmentService segmentService;
    private final CodeSequencePolicyService sequencePolicyService;
    private final CodeValueMappingService mappingService;
    private final Optional<PlatformModuleService> moduleService;
    private final Optional<ModuleMetadataRelationService> relationService;
    private final Optional<ModuleMetadataFieldService> moduleFieldService;
    private final Optional<MetadataService> metadataService;
    private final Optional<MetadataFieldService> fieldService;
    private final Optional<OrganizationHierarchyService> organizationHierarchyService;
    private final Optional<CodeTargetCatalog> staticTargets;

    public CodeRuleService(BaseDao<CodeRule, String> ruleDao,
                           CodeRuleSegmentService segmentService,
                           CodeSequencePolicyService sequencePolicyService,
                           CodeValueMappingService mappingService,
                           Optional<PlatformModuleService> moduleService,
                           Optional<ModuleMetadataRelationService> relationService,
                           Optional<ModuleMetadataFieldService> moduleFieldService,
                           Optional<MetadataService> metadataService,
                           Optional<MetadataFieldService> fieldService,
                           Optional<OrganizationHierarchyService> organizationHierarchyService) {
        this(ruleDao, segmentService, sequencePolicyService, mappingService, moduleService, relationService,
                moduleFieldService, metadataService, fieldService, organizationHierarchyService, Optional.empty());
    }

    @Autowired
    public CodeRuleService(BaseDao<CodeRule, String> ruleDao,
                           CodeRuleSegmentService segmentService,
                           CodeSequencePolicyService sequencePolicyService,
                           CodeValueMappingService mappingService,
                           Optional<PlatformModuleService> moduleService,
                           Optional<ModuleMetadataRelationService> relationService,
                           Optional<ModuleMetadataFieldService> moduleFieldService,
                           Optional<MetadataService> metadataService,
                           Optional<MetadataFieldService> fieldService,
                           Optional<OrganizationHierarchyService> organizationHierarchyService,
                           Optional<CodeTargetCatalog> staticTargets) {
        super(MODULE_ALIAS, CodeRule.class, ruleDao);
        this.segmentService = Objects.requireNonNull(segmentService, "segmentService must not be null");
        this.sequencePolicyService = Objects.requireNonNull(sequencePolicyService, "sequencePolicyService must not be null");
        this.mappingService = Objects.requireNonNull(mappingService, "mappingService must not be null");
        this.moduleService = moduleService == null ? Optional.empty() : moduleService;
        this.relationService = relationService == null ? Optional.empty() : relationService;
        this.moduleFieldService = moduleFieldService == null ? Optional.empty() : moduleFieldService;
        this.metadataService = metadataService == null ? Optional.empty() : metadataService;
        this.fieldService = fieldService == null ? Optional.empty() : fieldService;
        this.organizationHierarchyService = organizationHierarchyService == null ? Optional.empty() : organizationHierarchyService;
        this.staticTargets = Objects.requireNonNull(staticTargets, "staticTargets");
    }

    public CodeRuleService(BaseDao<CodeRule, String> ruleDao,
                           CodeRuleSegmentService segmentService,
                           CodeSequencePolicyService sequencePolicyService,
                           CodeValueMappingService mappingService) {
        this(ruleDao, segmentService, sequencePolicyService, mappingService,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public CodeRuleService(BaseDao<CodeRule, String> ruleDao,
                           CodeRuleSegmentService segmentService,
                           CodeSequencePolicyService sequencePolicyService,
                           CodeValueMappingService mappingService,
                           Optional<PlatformModuleService> moduleService,
                           Optional<ModuleMetadataRelationService> relationService,
                           Optional<MetadataService> metadataService,
                           Optional<MetadataFieldService> fieldService,
                           Optional<OrganizationHierarchyService> organizationHierarchyService) {
        this(ruleDao, segmentService, sequencePolicyService, mappingService, moduleService, relationService,
                Optional.empty(), metadataService, fieldService, organizationHierarchyService);
    }

    @Transactional
    public CodeRule saveRuleTree(CodeRule rule) {
        if (rule == null) {
            throw new PlatformException("Code rule tree must not be null");
        }
        List<CodeRuleSegment> segments = rule.getSegments() == null ? List.of() : new ArrayList<>(rule.getSegments());
        CodeSequencePolicy sequencePolicy = rule.getSequencePolicy();
        validateRuleTreeSemantics(rule, segments, sequencePolicy);
        boolean updating = rule.getId() != null && select(rule.getId()) != null;
        if (updating) {
            update(rule);
        } else {
            insert(rule);
        }
        replaceSequencePolicy(rule, sequencePolicy);
        replaceSegments(rule, segments);
        return viewRuleTree(rule.getId());
    }

    public CodeRule viewRuleTree(String ruleId) {
        CodeRule rule = select(ruleId);
        if (rule == null) {
            return null;
        }
        List<CodeRuleSegment> segments = segmentService.selectByRuleId(ruleId);
        for (CodeRuleSegment segment : segments) {
            segment.setMappings(mappingService.selectBySegmentId(segment.getId()));
        }
        rule.setSegments(segments);
        rule.setSequencePolicy(sequencePolicyService.selectByRuleId(ruleId));
        return rule;
    }

    public List<CodeRule> selectRuleTreesByBusinessObject(String moduleAlias, String entityAlias) {
        String normalizedModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        String normalizedEntityAlias = PlatformNameRules.requireIdentifier(entityAlias, "entityAlias");
        return list(Criteria.of()
                        .eq("moduleAlias", normalizedModuleAlias)
                        .eq("entityAlias", normalizedEntityAlias),
                ALL, Sort.asc("sortOrder")).stream()
                .map(rule -> viewRuleTree(rule.getId()))
                .toList();
    }

    public List<ResolvedCodeRule> resolveRules(ResolveCodeRuleCommand command) {
        validateResolveCommand(command);
        LocalDateTime effectiveAt = command.at() == null ? LocalDateTime.now() : command.at();
        List<CodeRule> candidates = list(Criteria.of()
                        .eq("moduleAlias", PlatformNameRules.requireModuleAlias(command.moduleAlias()))
                        .eq("entityAlias", PlatformNameRules.requireIdentifier(command.entityAlias(), "entityAlias")),
                ALL, Sort.asc("sortOrder")).stream()
                .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
                .filter(rule -> isEffective(rule, effectiveAt))
                .filter(rule -> matchesRequestedField(rule, command.moduleMetadataFieldId(),
                        command.metadataFieldId(), command.fieldName()))
                .map(rule -> viewRuleTree(rule.getId()))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, List<CodeRule>> byField = new LinkedHashMap<>();
        for (CodeRule candidate : candidates) {
            byField.computeIfAbsent(targetFieldKey(candidate), ignored -> new ArrayList<>()).add(candidate);
        }

        List<ResolvedCodeRule> resolved = new ArrayList<>();
        for (List<CodeRule> fieldCandidates : byField.values()) {
            ResolvedCodeRule rule = resolveForField(fieldCandidates, command.organizationId());
            if (rule != null) {
                resolved.add(rule);
            }
        }
        long primaryCount = resolved.stream()
                .filter(item -> item.rule().getFieldRole() == CodeFieldRole.PRIMARY)
                .map(item -> targetFieldKey(item.rule()))
                .distinct()
                .count();
        if (primaryCount > 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.only-one-primary-code-field-can-be-resolved-for-the-same-business",
                    "Only one PRIMARY code field can be resolved for the same business object"));
        }
        return resolved;
    }

    public ResolvedCodeRule resolveRule(ResolveCodeRuleCommand command) {
        List<ResolvedCodeRule> resolved = resolveRules(command);
        return resolved.stream()
                .filter(item -> item.rule().getFieldRole() == CodeFieldRole.PRIMARY)
                .findFirst()
                .orElse(resolved.isEmpty() ? null : resolved.getFirst());
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, CodeRule.class, java.util.List.of("id", "moduleAlias", "entityAlias", "metadataFieldId", "fieldName", "fieldRole", "mode", "orgScopeType", "orgScopeId", "globalDefault", "enabled", "effectiveFrom", "effectiveTo"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(CodeRule rule) {
        normalizeAndValidate(rule);
    }

    @Override
    public void beforeUpdate(CodeRule rule) {
        normalizeAndValidate(rule);
    }

    private void replaceSequencePolicy(CodeRule rule, CodeSequencePolicy incoming) {
        CodeSequencePolicy existing = sequencePolicyService.selectByRuleId(rule.getId());
        if (existing != null) {
            sequencePolicyService.delete(existing);
        }
        if (incoming == null) {
            return;
        }
        incoming.setId(null);
        incoming.setRuleId(rule.getId());
        sequencePolicyService.insert(incoming);
    }

    private void replaceSegments(CodeRule rule, List<CodeRuleSegment> incoming) {
        for (CodeRuleSegment existing : segmentService.selectByRuleId(rule.getId())) {
            for (CodeValueMapping mapping : mappingService.selectBySegmentId(existing.getId())) {
                mappingService.delete(mapping);
            }
            segmentService.delete(existing);
        }
        int sort = 1;
        for (CodeRuleSegment segment : incoming) {
            List<CodeValueMapping> mappings = segment.getMappings() == null ? List.of() : new ArrayList<>(segment.getMappings());
            segment.setId(null);
            segment.setRuleId(rule.getId());
            if (segment.getSortOrder() == null) {
                segment.setSortOrder(sort);
            }
            segmentService.insert(segment);
            int mappingSort = 1;
            for (CodeValueMapping mapping : mappings) {
                mapping.setId(null);
                mapping.setSegmentId(segment.getId());
                if (mapping.getSortOrder() == null) {
                    mapping.setSortOrder(mappingSort);
                }
                mappingService.insert(mapping);
                mappingSort++;
            }
            sort++;
        }
    }

    private void normalizeAndValidate(CodeRule rule) {
        rule.setModuleAlias(PlatformNameRules.requireModuleAlias(rule.getModuleAlias()));
        rule.setEntityAlias(PlatformNameRules.requireIdentifier(rule.getEntityAlias(), "entityAlias"));
        if (rule.getModuleMetadataFieldId() != null && !rule.getModuleMetadataFieldId().isBlank()) {
            rule.setModuleMetadataFieldId(rule.getModuleMetadataFieldId().trim());
        }
        if (rule.getMetadataFieldId() != null && !rule.getMetadataFieldId().isBlank()) {
            // A field ID is a persisted identity, not a SQL identifier (generated IDs may start with a digit).
            rule.setMetadataFieldId(rule.getMetadataFieldId().trim());
        }
        if (rule.getFieldName() != null && rule.getFieldName().isBlank()) {
            rule.setFieldName(null);
        }
        if (rule.getFieldName() != null) {
            rule.setFieldName(PlatformNameRules.requireFieldName(rule.getFieldName(), "fieldName"));
        }
        validateTargetExists(rule);
        rule.setFieldName(PlatformNameRules.requireFieldName(rule.getFieldName(), "fieldName"));
        if (rule.getTitle() == null || rule.getTitle().isBlank()) {
            rule.setTitle(rule.getFieldName());
        }
        if (rule.getFieldRole() == null) {
            rule.setFieldRole(CodeFieldRole.NORMAL);
        }
        if (rule.getMode() == null) {
            rule.setMode(CodeMode.AUTO);
        }
        if (rule.getOrgScopeType() == null) {
            rule.setOrgScopeType(CodeOrgScopeType.GLOBAL);
        }
        normalizeScope(rule);
        if (rule.getLinkedUpdate() == null) {
            rule.setLinkedUpdate(Boolean.FALSE);
        }
        if (rule.getAllowRecycle() == null) {
            rule.setAllowRecycle(Boolean.FALSE);
        }
        validatePrimaryUniqueness(rule);
        validateScopeOverlap(rule);
        validateSequencePolicy(rule);
    }

    private void normalizeScope(CodeRule rule) {
        if (rule.getOrgScopeType() == CodeOrgScopeType.GLOBAL) {
            rule.setGlobalDefault(Boolean.TRUE);
            rule.setOrgScopeId(null);
            return;
        }
        if (rule.getOrgScopeId() == null || rule.getOrgScopeId().isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.org-scoped-code-rule-requires-orgscopeid",
                    "ORG scoped code rule requires orgScopeId"));
        }
        rule.setGlobalDefault(Boolean.FALSE);
    }

    public Optional<net.ximatai.muyun.spring.ability.code.CodeAbility<?>> staticTarget(String moduleAlias, String entityAlias) {
        return staticTargets.flatMap(catalog -> catalog.find(moduleAlias, entityAlias));
    }

    private void validateTargetExists(CodeRule rule) {
        var staticService = staticTarget(rule.getModuleAlias(), rule.getEntityAlias());
        if (staticService.isPresent()) {
            if (hasText(rule.getMetadataFieldId()) || hasText(rule.getModuleMetadataFieldId()))
                throw new PlatformException("Static code targets do not accept dynamic metadata identifiers");
            var field = StaticCodeFields.declarations(staticService.get().modelClass()).get(rule.getFieldName());
            if (field == null) throw new PlatformException("Undeclared static business code field: " + rule.getFieldName());
            rule.setFieldRole(field.primary() ? CodeFieldRole.PRIMARY : CodeFieldRole.NORMAL);
            return;
        }
        moduleService.ifPresent(service -> {
            if (service.resolveVisibleModule(rule.getModuleAlias()) == null) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.code-rule.code-rule-requires-existing-module",
                        "Code rule requires existing module: {value1}"), Map.of("value1", String.valueOf(rule.getModuleAlias())));
            }
        });
        if (relationService.isEmpty() || fieldService.isEmpty()) {
            return;
        }
        ResolvedModuleMetadataField resolvedModuleField = resolveModuleMetadataField(rule);
        if (resolvedModuleField != null) {
            if (!Objects.equals(resolvedModuleField.moduleAlias(), rule.getModuleAlias())
                    || !Objects.equals(resolvedModuleField.metadataAlias(), rule.getEntityAlias())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.code-rule.code-rule-modulemetadatafieldid-does-not-belong-to-target-object",
                        "Code rule moduleMetadataFieldId does not belong to target object: {value1}"), Map.of("value1", String.valueOf(rule.getModuleMetadataFieldId())));
            }
            rule.setMetadataFieldId(resolvedModuleField.metadataFieldId());
            rule.setFieldName(resolvedModuleField.fieldName());
            return;
        }
        if (metadataService.isEmpty()) throw new PlatformException("Code target resolution requires metadata facts");
        ModuleMetadataRelation relation = relationService.get().list(Criteria.of().eq("moduleAlias", rule.getModuleAlias()), ALL)
                .stream().filter(candidate -> {
                    Metadata metadata = metadataService.get().select(candidate.getMetadataId());
                    return metadata != null && Objects.equals(metadata.getAlias(), rule.getEntityAlias());
                }).findFirst().orElse(null);
        if (relation == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-rule-requires-existing-module-entity",
                    "Code rule requires existing module entity: {value1}/{value2}"), Map.of("value1", String.valueOf(rule.getModuleAlias()), "value2", String.valueOf(rule.getEntityAlias())));
        }
        MetadataField field = resolveField(relation.getMetadataId(), rule);
        if (field == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-rule-requires-existing-target-field",
                    "Code rule requires existing target field: {value1}.{value2}"), Map.of("value1", String.valueOf(rule.getEntityAlias()), "value2", String.valueOf(rule.getFieldName())));
        }
        if (rule.getMetadataFieldId() == null || rule.getMetadataFieldId().isBlank()) {
            rule.setMetadataFieldId(field.getId());
        }
        rule.setFieldName(field.getFieldName());
    }

    private ResolvedModuleMetadataField resolveModuleMetadataField(CodeRule rule) {
        if (rule.getModuleMetadataFieldId() == null || rule.getModuleMetadataFieldId().isBlank()) {
            return null;
        }
        if (moduleFieldService.isEmpty()) {
            throw new PlatformException("Code rule moduleMetadataFieldId requires ModuleMetadataFieldService");
        }
        return moduleFieldService.get().resolve(rule.getModuleMetadataFieldId());
    }

    private MetadataField resolveField(String metadataId, CodeRule rule) {
        if (rule.getMetadataFieldId() != null && !rule.getMetadataFieldId().isBlank()) {
            MetadataField byId = fieldService.get().select(rule.getMetadataFieldId());
            if (byId == null || !Objects.equals(byId.getMetadataId(), metadataId)
                    || (hasText(rule.getFieldName()) && !Objects.equals(rule.getFieldName(), byId.getFieldName())))
                throw new PlatformException("Code rule target field identity does not match the declared entity");
            return byId;
        }
        return fieldService.get().list(Criteria.of()
                        .eq("metadataId", metadataId)
                        .eq("fieldName", rule.getFieldName()),
                ALL).stream().findFirst().orElse(null);
    }

    private void validatePrimaryUniqueness(CodeRule rule) {
        if (rule.getFieldRole() != CodeFieldRole.PRIMARY || Boolean.FALSE.equals(rule.getEnabled())) {
            return;
        }
        List<CodeRule> existingPrimaryRules = list(Criteria.of()
                .eq("moduleAlias", rule.getModuleAlias())
                .eq("entityAlias", rule.getEntityAlias())
                .eq("fieldRole", CodeFieldRole.PRIMARY)
                .eq("enabled", Boolean.TRUE), ALL);
        String currentFieldKey = targetFieldKey(rule);
        for (CodeRule existing : existingPrimaryRules) {
            if (Objects.equals(existing.getId(), rule.getId())) {
                continue;
            }
            if (!Objects.equals(targetFieldKey(existing), currentFieldKey)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.code-rule.business-object-can-only-have-one-primary-code-field",
                        "Business object can only have one PRIMARY code field: {value1}/{value2}"), Map.of("value1", String.valueOf(rule.getModuleAlias()), "value2", String.valueOf(rule.getEntityAlias())));
            }

        }
    }

    private void validateScopeOverlap(CodeRule rule) {
        if (Boolean.FALSE.equals(rule.getEnabled())) return;
        if (rule.getEffectiveFrom() != null && rule.getEffectiveTo() != null && rule.getEffectiveFrom().isAfter(rule.getEffectiveTo()))
            throw new PlatformException("Code rule effective interval is invalid");
        for (CodeRule existing : list(Criteria.of().eq("moduleAlias", rule.getModuleAlias())
                .eq("entityAlias", rule.getEntityAlias()).eq("enabled", true), ALL)) {
            if (Objects.equals(existing.getId(), rule.getId()) || !Objects.equals(targetFieldKey(existing), targetFieldKey(rule))
                    || !sameScope(existing, rule)) continue;
            boolean overlaps = (rule.getEffectiveFrom() == null || existing.getEffectiveTo() == null || !rule.getEffectiveFrom().isAfter(existing.getEffectiveTo()))
                    && (existing.getEffectiveFrom() == null || rule.getEffectiveTo() == null || !existing.getEffectiveFrom().isAfter(rule.getEffectiveTo()));
            if (overlaps) throw new PlatformException("Code rule already exists for target field in the same scope");
        }
    }

    private boolean sameScope(CodeRule left, CodeRule right) {
        return Objects.equals(left.getOrgScopeType(), right.getOrgScopeType())
                && Objects.equals(left.getOrgScopeId(), right.getOrgScopeId());
    }

    private void validateSequencePolicy(CodeRule rule) {
        boolean hasSequence = rule.getSegments() != null && rule.getSegments().stream()
                .anyMatch(segment -> segment != null && segment.getSegmentType() == CodeSegmentType.SEQUENCE);
        if (hasSequence && rule.getSequencePolicy() == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-rule-with-sequence-segment-requires-sequencepolicy",
                    "Code rule with SEQUENCE segment requires sequencePolicy"));
        }
    }

    private void validateRuleTreeSemantics(CodeRule rule, List<CodeRuleSegment> segments, CodeSequencePolicy sequencePolicy) {
        CodeMode mode = rule.getMode() == null ? CodeMode.AUTO : rule.getMode();
        if (mode == CodeMode.MANUAL) {
            if (sequencePolicy != null || segments.stream().anyMatch(this::isSequenceSegment)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.code-rule.manual-code-rule-cannot-declare-automatic-sequence-configuration",
                        "MANUAL code rule cannot declare automatic sequence configuration"));
            }
            return;
        }
        if (segments.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.automatic-code-rule-requires-at-least-one-segment",
                    "Automatic code rule requires at least one segment"));
        }
        boolean hasSequence = segments.stream().anyMatch(this::isSequenceSegment);
        if (hasSequence && sequencePolicy == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-rule-with-sequence-segment-requires-sequencepolicy",
                    "Code rule with SEQUENCE segment requires sequencePolicy"));
        }
        if (!hasSequence && sequencePolicy != null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-sequence-policy-requires-a-sequence-segment",
                    "Code sequence policy requires a SEQUENCE segment"));
        }
        if (sequencePolicy != null) {
            validateDraftSequencePolicy(sequencePolicy);
        }
        if (Boolean.TRUE.equals(rule.getLinkedUpdate()) && !hasLinkedDependency(segments)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.linkedupdate-code-rule-requires-at-least-one-source-or-formula-segment",
                    "linkedUpdate code rule requires at least one source or formula segment"));
        }
        for (CodeRuleSegment segment : segments) {
            validateDraftSegment(segment);
        }
    }

    private boolean isSequenceSegment(CodeRuleSegment segment) {
        return segment != null && segment.getSegmentType() == CodeSegmentType.SEQUENCE;
    }

    private boolean hasLinkedDependency(List<CodeRuleSegment> segments) {
        return segments.stream()
                .filter(Objects::nonNull)
                .anyMatch(segment -> segment.getSegmentType() == CodeSegmentType.FORMULA
                        || Set.of(CodeSegmentType.FIELD_VALUE, CodeSegmentType.CONTEXT_VAR, CodeSegmentType.VALUE_MAPPING)
                        .contains(segment.getSegmentType()) && hasText(segment.getSourceRef()));
    }

    private void validateDraftSequencePolicy(CodeSequencePolicy policy) {
        if (policy.getStartValue() != null && policy.getStartValue() < 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-sequence-policy-startvalue-must-not-be-negative",
                    "Code sequence policy startValue must not be negative"));
        }
        if (policy.getStepValue() != null && policy.getStepValue() <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-sequence-policy-stepvalue-must-be-positive",
                    "Code sequence policy stepValue must be positive"));
        }
        if (policy.getSequenceLength() != null && policy.getSequenceLength() <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-sequence-policy-sequencelength-must-be-positive",
                    "Code sequence policy sequenceLength must be positive"));
        }
        if (policy.getMaxValue() != null && policy.getStartValue() != null
                && policy.getMaxValue() < policy.getStartValue()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-sequence-policy-maxvalue-must-be-greater-than-or-equal-to-startvalue",
                    "Code sequence policy maxValue must be greater than or equal to startValue"));
        }
    }

    private void validateDraftSegment(CodeRuleSegment segment) {
        if (segment == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-rule-segment-must-not-be-null",
                    "Code rule segment must not be null"));
        }
        if (segment.getSegmentType() == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.code-segment-requires-segmenttype",
                    "Code segment requires segmentType"));
        }
        switch (segment.getSegmentType()) {
            case CONSTANT -> {
                if (!hasText(segment.getFixedValue())) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.code-rule.constant-code-segment-requires-fixedvalue",
                            "CONSTANT code segment requires fixedValue"));
                }
            }
            case FIELD_VALUE, CONTEXT_VAR -> requireSourceRef(segment);
            case VALUE_MAPPING -> {
                requireSourceRef(segment);
                validateValueMappings(segment);
            }
            case FORMULA -> {
                if (!hasText(segment.getFormulaExpr())) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.code-rule.formula-code-segment-requires-formulaexpr",
                            "FORMULA code segment requires formulaExpr"));
                }
            }
            case SEQUENCE -> {
                if (segment.getLength() != null && segment.getLength() <= 0) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.code-rule.sequence-code-segment-length-must-be-positive",
                            "SEQUENCE code segment length must be positive"));
                }
            }
            case SYSTEM_TIME -> {
            }
        }
    }

    private void requireSourceRef(CodeRuleSegment segment) {
        if (!hasText(segment.getSourceRef())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.source-reference-required",
                    "{value1} code segment requires sourceRef"), Map.of("value1", String.valueOf(segment.getSegmentType())));
        }
    }

    private void validateValueMappings(CodeRuleSegment segment) {
        List<CodeValueMapping> mappings = segment.getMappings() == null ? List.of() : segment.getMappings();
        if (mappings.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.value-mapping-code-segment-requires-mappings",
                    "VALUE_MAPPING code segment requires mappings"));
        }
        boolean defaultSeen = false;
        Set<String> sourceValues = new HashSet<>();
        for (CodeValueMapping mapping : mappings) {
            if (mapping == null) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.code-rule.code-value-mapping-must-not-be-null",
                        "Code value mapping must not be null"));
            }
            if (mapping.getTargetValue() == null) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.code-rule.code-value-mapping-requires-targetvalue",
                        "Code value mapping requires targetValue"));
            }
            if (Boolean.TRUE.equals(mapping.getDefaultMapping())) {
                if (defaultSeen) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.code-rule.value-mapping-code-segment-can-only-have-one-default-mapping",
                            "VALUE_MAPPING code segment can only have one default mapping"));
                }
                defaultSeen = true;
            } else {
                if (!hasText(mapping.getSourceValue())) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.code-rule.code-value-mapping-requires-sourcevalue",
                            "Code value mapping requires sourceValue"));
                }
                if (!sourceValues.add(mapping.getSourceValue())) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.code-rule.value-mapping-code-segment-has-duplicate-sourcevalue",
                            "VALUE_MAPPING code segment has duplicate sourceValue: {value1}"), Map.of("value1", String.valueOf(mapping.getSourceValue())));
                }
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ResolvedCodeRule resolveForField(List<CodeRule> candidates, String organizationId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (String currentOrganizationId : organizationChain(organizationId)) {
            List<CodeRule> scoped = candidates.stream()
                    .filter(rule -> rule.getOrgScopeType() == CodeOrgScopeType.ORG)
                    .filter(rule -> Objects.equals(rule.getOrgScopeId(), currentOrganizationId))
                    .toList();
            if (!scoped.isEmpty()) {
                return new ResolvedCodeRule(assertSingleScopedRule(scoped), currentOrganizationId);
            }
        }
        List<CodeRule> global = candidates.stream()
                .filter(rule -> rule.getOrgScopeType() == CodeOrgScopeType.GLOBAL)
                .filter(rule -> Boolean.TRUE.equals(rule.getGlobalDefault()))
                .filter(rule -> rule.getOrgScopeId() == null || rule.getOrgScopeId().isBlank())
                .toList();
        return global.isEmpty() ? null : new ResolvedCodeRule(assertSingleScopedRule(global), null);
    }

    private CodeRule assertSingleScopedRule(List<CodeRule> rules) {
        if (rules.size() > 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-rule.multiple-code-rules-matched-in-the-same-scope",
                    "Multiple code rules matched in the same scope"));
        }
        return rules.getFirst();
    }

    private LinkedHashSet<String> organizationChain(String organizationId) {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        if (organizationId == null || organizationId.isBlank()) {
            return chain;
        }
        chain.add(organizationId);
        organizationHierarchyService.ifPresent(service -> chain.addAll(service.organizationIdsFromSelfToRoot(organizationId)));
        return chain;
    }

    private boolean isEffective(CodeRule rule, LocalDateTime at) {
        return (rule.getEffectiveFrom() == null || !rule.getEffectiveFrom().isAfter(at))
                && (rule.getEffectiveTo() == null || !rule.getEffectiveTo().isBefore(at));
    }

    private boolean matchesRequestedField(CodeRule rule,
                                          String moduleMetadataFieldId,
                                          String metadataFieldId,
                                          String fieldName) {
        if (moduleMetadataFieldId != null && !moduleMetadataFieldId.isBlank()) {
            return Objects.equals(rule.getModuleMetadataFieldId(), moduleMetadataFieldId);
        }
        if (metadataFieldId != null && !metadataFieldId.isBlank()) {
            return Objects.equals(rule.getMetadataFieldId(), metadataFieldId);
        }
        if (fieldName != null && !fieldName.isBlank()) {
            return Objects.equals(rule.getFieldName(), fieldName);
        }
        return true;
    }

    private String targetFieldKey(CodeRule rule) {
        // Configuration IDs locate the field; the normalized business target has one identity.
        return rule.getFieldName();
    }

    private void validateResolveCommand(ResolveCodeRuleCommand command) {
        if (command == null) {
            throw new PlatformException("Code rule resolve command must not be null");
        }
        PlatformNameRules.requireModuleAlias(command.moduleAlias());
        PlatformNameRules.requireIdentifier(command.entityAlias(), "entityAlias");
    }
}
