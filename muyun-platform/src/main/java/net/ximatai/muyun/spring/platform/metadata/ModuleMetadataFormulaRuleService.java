package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaIssueLevel;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.dynamic.metadata.EntityFormulaRuleDefinition;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class ModuleMetadataFormulaRuleService extends AbstractAbilityService<ModuleMetadataFormulaRule> implements
        SoftDeleteAbility<ModuleMetadataFormulaRule>,
        EnableAbility<ModuleMetadataFormulaRule>,
        SortAbility<ModuleMetadataFormulaRule>,
        QueryAbility<ModuleMetadataFormulaRule> {
    public static final String MODULE_ALIAS = "platform.module_metadata_formula_rule";

    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ModuleMetadataRelationService relationService;
    private final FormulaEngine formulaEngine = new FormulaEngine();
    private final MetadataFormulaFieldValidator fieldValidator;
    private final PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator;

    public ModuleMetadataFormulaRuleService(BaseDao<ModuleMetadataFormulaRule, String> formulaRuleDao,
                                            ModuleMetadataRelationService relationService,
                                            MetadataFieldService fieldService) {
        this(formulaRuleDao, relationService, fieldService, Optional.empty(), Optional.empty());
    }

    /** Compatibility constructor for callers that only configure the refresh hook. */
    public ModuleMetadataFormulaRuleService(BaseDao<ModuleMetadataFormulaRule, String> formulaRuleDao,
                                            ModuleMetadataRelationService relationService,
                                            MetadataFieldService fieldService,
                                            Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        this(formulaRuleDao, relationService, fieldService, runtimeRefreshCoordinator, Optional.empty());
    }

    @Autowired
    public ModuleMetadataFormulaRuleService(BaseDao<ModuleMetadataFormulaRule, String> formulaRuleDao,
                                            ModuleMetadataRelationService relationService,
                                            MetadataFieldService fieldService,
                                            Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator,
                                            Optional<MetadataFieldReferenceConfigService> referenceConfigService) {
        super(MODULE_ALIAS, ModuleMetadataFormulaRule.class, formulaRuleDao);
        this.relationService = relationService;
        this.fieldValidator = new MetadataFormulaFieldValidator(relationService, fieldService,
                referenceConfigService.orElse(null));
        this.runtimeRefreshCoordinator = runtimeRefreshCoordinator.orElse(null);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, ModuleMetadataFormulaRule.class, java.util.List.of("id", "relationId", "alias", "ruleKind", "rulePhase", "targetField", "severity", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    @Transactional
    public String insert(ModuleMetadataFormulaRule rule) {
        return SoftDeleteAbility.super.insert(rule);
    }

    @Override
    @Transactional
    public List<String> insertBatch(Collection<ModuleMetadataFormulaRule> rules) {
        return SoftDeleteAbility.super.insertBatch(rules);
    }

    @Override
    @Transactional
    public int update(ModuleMetadataFormulaRule rule) {
        return SoftDeleteAbility.super.update(rule);
    }

    @Override
    @Transactional
    public int updateWithExisting(ModuleMetadataFormulaRule rule, ModuleMetadataFormulaRule existing) {
        return SoftDeleteAbility.super.updateWithExisting(rule, existing);
    }

    @Override
    @Transactional
    public int enable(String id) {
        return EnableAbility.super.enable(id);
    }

    @Override
    @Transactional
    public int enable(String id, Integer expectedVersion) {
        return EnableAbility.super.enable(id, expectedVersion);
    }

    @Override
    @Transactional
    public int disable(String id) {
        return EnableAbility.super.disable(id);
    }

    @Override
    @Transactional
    public int disable(String id, Integer expectedVersion) {
        return EnableAbility.super.disable(id, expectedVersion);
    }

    @Override
    @Transactional
    public int delete(String id) {
        return SoftDeleteAbility.super.delete(id);
    }

    @Override
    @Transactional
    public int delete(ModuleMetadataFormulaRule rule) {
        return SoftDeleteAbility.super.delete(rule);
    }

    @Override
    @Transactional
    public int delete(String id, Integer expectedVersion) {
        return SoftDeleteAbility.super.delete(id, expectedVersion);
    }

    @Override
    @Transactional
    public int delete(String id, Integer expectedVersion, DeletionContext deletionContext) {
        return SoftDeleteAbility.super.delete(id, expectedVersion, deletionContext);
    }

    @Override
    @Transactional
    public int deleteBatch(Collection<String> ids) {
        return SoftDeleteAbility.super.deleteBatch(ids);
    }

    @Override
    @Transactional
    public int deleteBatch(Collection<String> ids, DeletionContext deletionContext) {
        return SoftDeleteAbility.super.deleteBatch(ids, deletionContext);
    }

    @Override
    @Transactional
    public int restore(String id) {
        return SoftDeleteAbility.super.restore(id);
    }

    @Override
    @Transactional
    public int restore(String id, Integer expectedVersion) {
        return SoftDeleteAbility.super.restore(id, expectedVersion);
    }

    @Override
    @Transactional
    public void reorder(List<String> orderedIds) {
        SortAbility.super.reorder(orderedIds);
    }

    @Override
    @Transactional
    public void reorder(Criteria additionalScope, List<String> orderedIds) {
        SortAbility.super.reorder(additionalScope, orderedIds);
    }

    @Override
    @Transactional
    public void moveBefore(String id, String beforeId) {
        SortAbility.super.moveBefore(id, beforeId);
    }

    @Override
    @Transactional
    public void moveAfter(String id, String afterId) {
        SortAbility.super.moveAfter(id, afterId);
    }

    @Override
    @Transactional
    public boolean moveBetween(ModuleMetadataFormulaRule moving, ModuleMetadataFormulaRule previous,
                               ModuleMetadataFormulaRule next) {
        return SortAbility.super.moveBetween(moving, previous, next);
    }

    @Override
    public void beforeInsert(ModuleMetadataFormulaRule rule) {
        touchOwningRelation(rule == null ? null : rule.getRelationId());
        normalizeAndValidate(rule);
    }

    @Override
    public void beforeUpdate(ModuleMetadataFormulaRule rule, ModuleMetadataFormulaRule existing) {
        if (existing != null && !Objects.equals(existing.getRelationId(), rule.getRelationId())) {
            throw new PlatformException("Metadata formula rule relation cannot be changed: " + rule.getId());
        }
        String relationId = existing == null ? null : existing.getRelationId();
        if (relationId == null && rule != null) {
            relationId = rule.getRelationId();
        }
        touchOwningRelation(relationId);
        normalizeAndValidate(rule);
    }

    @Override
    public void beforeDelete(String id) {
        ModuleMetadataFormulaRule existing = selectIgnoreSoftDelete(id);
        if (existing != null && !Boolean.TRUE.equals(existing.getDeleted())) {
            touchOwningRelation(existing.getRelationId());
        }
    }

    @Override
    public void beforeRestore(String id) {
        ModuleMetadataFormulaRule existing = selectIgnoreSoftDelete(id);
        if (existing != null && Boolean.TRUE.equals(existing.getDeleted())) {
            touchOwningRelation(existing.getRelationId());
        }
    }

    @Override
    public void afterChanged(ModuleMetadataFormulaRule rule) {
        if (runtimeRefreshCoordinator != null && !MetadataCapabilityGovernanceMutationContext.isActive()) {
            runtimeRefreshCoordinator.refreshByFormulaRule(rule);
        }
    }

    public List<ModuleMetadataFormulaRule> listByRelationIds(List<String> relationIds) {
        if (relationIds == null || relationIds.isEmpty()) {
            return List.of();
        }
        return list(Criteria.of().in("relationId", relationIds), ALL, Sort.asc("sortOrder"));
    }

    public EntityFormulaRuleDefinition compile(ModuleMetadataFormulaRule rule) {
        return new EntityFormulaRuleDefinition(
                rule.getAlias(),
                rule.getExpression(),
                rule.getRuleKind(),
                rule.getRulePhase(),
                rule.getTargetField(),
                rule.getSeverity(),
                rule.getMessageTemplate(),
                Boolean.TRUE.equals(rule.getStopOnError()),
                Boolean.TRUE.equals(rule.getEnabled()),
                rule.getSortOrder() == null ? 0 : rule.getSortOrder()
        );
    }

    /**
     * Formula rules and governance apply share their owning relation's optimistic version.
     * Governance already claims that version before it persists its own rule batch.
     */
    private void touchOwningRelation(String relationId) {
        if (MetadataCapabilityGovernanceMutationContext.isActive()) {
            return;
        }
        ModuleMetadataRelation relation = relationId == null || relationId.isBlank() ? null : relationService.select(relationId);
        if (relation == null) {
            throw new PlatformException("Metadata formula rule requires existing relation: " + relationId);
        }
        MetadataCapabilityGovernanceMutationContext.run(() -> {
            relationService.update(relation);
            return null;
        });
    }

    private void normalizeAndValidate(ModuleMetadataFormulaRule rule) {
        ModuleMetadataRelation relation = rule.getRelationId() == null || rule.getRelationId().isBlank()
                ? null
                : relationService.select(rule.getRelationId());
        if (relation == null) {
            throw new PlatformException("Metadata formula rule requires existing relation: " + rule.getRelationId());
        }
        if (rule.getAlias() == null || !rule.getAlias().matches("[a-z][A-Za-z0-9]{0,63}")) {
            throw new PlatformException("Metadata formula rule requires valid alias: " + rule.getAlias());
        }
        if (rule.getRuleKind() == null) {
            rule.setRuleKind(FormulaRuleKind.VALIDATION);
        }
        if (rule.getRulePhase() == null) {
            rule.setRulePhase(FormulaRulePhase.BEFORE_SAVE);
        }
        if (rule.getExpression() == null || rule.getExpression().isBlank()) {
            throw new PlatformException("Metadata formula rule requires expression: " + rule.getAlias());
        }
        rule.setExpression(rule.getExpression().trim());
        if (rule.getTargetField() != null && rule.getTargetField().isBlank()) {
            rule.setTargetField(null);
        }
        if (rule.getSeverity() == null) {
            rule.setSeverity(FormulaIssueLevel.ERROR);
        }
        if (rule.getMessageTemplate() != null && rule.getMessageTemplate().isBlank()) {
            rule.setMessageTemplate(null);
        }
        if (rule.getStopOnError() == null) {
            rule.setStopOnError(rule.getRuleKind() == FormulaRuleKind.VALIDATION);
        }
        validateFormula(rule, relation);
        rejectDuplicate(rule, Criteria.of()
                        .eq("relationId", rule.getRelationId())
                        .eq("alias", rule.getAlias()),
                "module metadata formula rule must be unique in relation: "
                        + rule.getRelationId() + "." + rule.getAlias());
    }

    private void validateFormula(ModuleMetadataFormulaRule rule, ModuleMetadataRelation relation) {
        try {
            if (formulaEngine.parse(rule.getAlias(), rule.getExpression()) == null) {
                throw new PlatformException("Metadata formula expression is invalid: " + rule.getAlias());
            }
            if (rule.getRuleKind() != FormulaRuleKind.CALCULATION
                    && formulaEngine.containsAssignment(rule.getExpression())) {
                throw new PlatformException("Metadata formula expression must not assign fields: " + rule.getAlias());
            }
            if (rule.getRulePhase() == FormulaRulePhase.IMPORT_VALIDATE
                    && rule.getRuleKind() == FormulaRuleKind.CALCULATION) {
                throw new PlatformException("Metadata import validation formula must not calculate fields: "
                        + rule.getAlias());
            }
            formulaEngine.validateTargetFieldExpressionScope(rule.getTargetField(), rule.getExpression());
            fieldValidator.validateTargetField(rule.getTargetField(), relation, "Metadata formula");
            fieldValidator.validateExpressionFields(formulaEngine.referencedFields(rule.getExpression()), relation,
                    "Metadata formula");
        } catch (FormulaEvaluationException exception) {
            throw new PlatformException("Metadata formula expression is invalid: "
                    + rule.getAlias() + ", " + exception.getMessage(), exception);
        }
    }
}
