package net.ximatai.muyun.spring.platform.measure;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.TenantLayerAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.application.ApplicationReferenceContributor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class MeasureUnitConversionRuleService extends StandardBusinessService<MeasureUnitConversionRule> implements
        TenantLayerAbility<MeasureUnitConversionRule>,
        SoftDeleteAbility<MeasureUnitConversionRule>,
        EnableAbility<MeasureUnitConversionRule>,
        SortAbility<MeasureUnitConversionRule>,
        ReferenceAbility<MeasureUnitConversionRule>,
        QueryAbility<MeasureUnitConversionRule>,
        ApplicationReferenceContributor {
    public static final String MODULE_ALIAS = "platform.measure_unit_conversion_rule";

    private final MeasureUnitService unitService;

    public MeasureUnitConversionRuleService(BaseDao<MeasureUnitConversionRule, String> ruleDao,
                                            MeasureUnitService unitService) {
        super(MODULE_ALIAS, MeasureUnitConversionRule.class, ruleDao);
        this.unitService = unitService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, MeasureUnitConversionRule.class, java.util.List.of("id", "tenantId", "applicationAlias", "scopeType", "moduleAlias", "contextObjectType", "contextObjectId", "fromCategoryAlias", "fromUnitCode", "toCategoryAlias", "toUnitCode", "factor", "priority", "effectiveFrom", "effectiveTo", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.desc("priority"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("title"));
    }

    @Override
    public String resourceKey() {
        return "measureUnitConversionRule";
    }

    @Override
    public String resourceName() {
        return "计量单位换算规则";
    }

    @Override
    public boolean hasReferenceTo(String applicationAlias) {
        return findOne(Criteria.of().eq("applicationAlias", applicationAlias)) != null;
    }

    @Override
    protected void validateBeforeUpdate(MeasureUnitConversionRule rule, MeasureUnitConversionRule existing) {
        validateImmutableIdentity(rule, existing);
    }

    public List<MeasureUnitConversionRule> applicableRules(MeasureUnitConversionContext context) {
        MeasureUnitConversionContext validContext = normalizeContext(context);
        LocalDateTime operatedAt = validContext.operatedAt() == null ? LocalDateTime.now() : validContext.operatedAt();
        return listVisibleRules(validContext)
                .stream()
                .filter(rule -> isApplicable(rule, validContext, operatedAt))
                .toList();
    }

    @Override
    protected void validateBeforeSave(MeasureUnitConversionRule rule) {
        rule.setApplicationAlias(PlatformNameRules.requireApplicationAlias(rule.getApplicationAlias()));
        if (rule.getScopeType() == null) {
            rule.setScopeType(MeasureUnitConversionScopeType.GLOBAL);
        }
        normalizeScope(rule);
        rule.setFromCategoryAlias(requireCategoryAlias(rule.getFromCategoryAlias(), "fromCategoryAlias"));
        rule.setFromUnitCode(requireCode(rule.getFromUnitCode(), "fromUnitCode"));
        rule.setToCategoryAlias(requireCategoryAlias(rule.getToCategoryAlias(), "toCategoryAlias"));
        rule.setToUnitCode(requireCode(rule.getToUnitCode(), "toUnitCode"));
        unitService.requireVisibleUnit(rule.getApplicationAlias(), rule.getFromCategoryAlias(), rule.getFromUnitCode());
        unitService.requireVisibleUnit(rule.getApplicationAlias(), rule.getToCategoryAlias(), rule.getToUnitCode());
        if (rule.getFromCategoryAlias().equals(rule.getToCategoryAlias())
                && rule.getFromUnitCode().equals(rule.getToUnitCode())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-source-and-target-must-be-different",
                    "measure conversion rule source and target must be different"));
        }
        if (rule.getFactor() == null) {
            rule.setFactor(BigDecimal.ONE);
        }
        if (rule.getFactor().compareTo(BigDecimal.ZERO) <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-factor-must-be-positive",
                    "measure conversion rule factor must be positive"));
        }
        if (rule.getPriority() == null) {
            rule.setPriority(0);
        }
        if (rule.getEffectiveFrom() != null && rule.getEffectiveTo() != null
                && !rule.getEffectiveFrom().isBefore(rule.getEffectiveTo())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-effectivefrom-must-be-before-effectiveto",
                    "measure conversion rule effectiveFrom must be before effectiveTo"));
        }
        validateNoOverlappingRule(rule);
    }

    private void normalizeScope(MeasureUnitConversionRule rule) {
        switch (rule.getScopeType()) {
            case GLOBAL -> {
                rule.setModuleAlias(null);
                rule.setContextObjectType(null);
                rule.setContextObjectId(null);
            }
            case MODULE -> {
                rule.setModuleAlias(PlatformNameRules.requireModuleAlias(rule.getModuleAlias()));
                rule.setContextObjectType(null);
                rule.setContextObjectId(null);
            }
            case RECORD_CONTEXT -> {
                rule.setModuleAlias(PlatformNameRules.requireModuleAlias(rule.getModuleAlias()));
                rule.setContextObjectType(requireCode(rule.getContextObjectType(), "contextObjectType"));
                if (rule.getContextObjectId() == null || rule.getContextObjectId().isBlank()) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-contextobjectid-must-not-be-blank",
                            "measure conversion rule contextObjectId must not be blank"));
                }
            }
        }
    }

    private void validateImmutableIdentity(MeasureUnitConversionRule rule, MeasureUnitConversionRule existing) {
        rejectChangedText(existing, rule, TextReference.code(
                "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-application-cannot-be-changed",
                "Measure conversion rule application cannot be changed"), MeasureUnitConversionRule::getApplicationAlias);
        rejectChangedText(existing, rule, TextReference.code(
                "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-scope-type-cannot-be-changed",
                "Measure conversion rule scope type cannot be changed"), MeasureUnitConversionRule::getScopeType);
        rejectChangedText(existing, rule, TextReference.code(
                "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-module-cannot-be-changed",
                "Measure conversion rule module cannot be changed"), MeasureUnitConversionRule::getModuleAlias);
        rejectChangedText(existing, rule, TextReference.code(
                "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-context-type-cannot-be-changed",
                "Measure conversion rule context type cannot be changed"), MeasureUnitConversionRule::getContextObjectType);
        rejectChangedText(existing, rule, TextReference.code(
                "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-context-id-cannot-be-changed",
                "Measure conversion rule context id cannot be changed"), MeasureUnitConversionRule::getContextObjectId);
        rejectChangedText(existing, rule, TextReference.code(
                "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-source-category-cannot-be-changed",
                "Measure conversion rule source category cannot be changed"), MeasureUnitConversionRule::getFromCategoryAlias);
        rejectChangedText(existing, rule, TextReference.code(
                "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-source-unit-cannot-be-changed",
                "Measure conversion rule source unit cannot be changed"), MeasureUnitConversionRule::getFromUnitCode);
        rejectChangedText(existing, rule, TextReference.code(
                "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-target-category-cannot-be-changed",
                "Measure conversion rule target category cannot be changed"), MeasureUnitConversionRule::getToCategoryAlias);
        rejectChangedText(existing, rule, TextReference.code(
                "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-target-unit-cannot-be-changed",
                "Measure conversion rule target unit cannot be changed"), MeasureUnitConversionRule::getToUnitCode);
    }

    private boolean isApplicable(MeasureUnitConversionRule rule,
                                 MeasureUnitConversionContext context,
                                 LocalDateTime operatedAt) {
        if (rule.getEffectiveFrom() != null && operatedAt.isBefore(rule.getEffectiveFrom())) {
            return false;
        }
        if (rule.getEffectiveTo() != null && !operatedAt.isBefore(rule.getEffectiveTo())) {
            return false;
        }
        return switch (rule.getScopeType()) {
            case GLOBAL -> true;
            case MODULE -> rule.getModuleAlias().equals(context.moduleAlias());
            case RECORD_CONTEXT -> rule.getModuleAlias().equals(context.moduleAlias())
                    && rule.getContextObjectType().equals(context.contextObjectType())
                    && rule.getContextObjectId().equals(context.contextObjectId());
        };
    }

    private List<MeasureUnitConversionRule> listVisibleRules(MeasureUnitConversionContext context) {
        List<MeasureUnitConversionRule> tenantRules = new ArrayList<>();
        List<MeasureUnitConversionRule> globalRules = new ArrayList<>();
        for (String applicationAlias : applicationCandidates(context.applicationAlias())) {
            Criteria criteria = Criteria.of().eq("applicationAlias", applicationAlias).eq("enabled", Boolean.TRUE);
            Sort[] order = {Sort.desc("priority"), Sort.asc(PlatformAbilityFields.SORT_FIELD)};
            tenantRules.addAll(listCurrentTenant(criteria, order));
            globalRules.addAll(listGlobal(criteria, order));
        }
        tenantRules.addAll(globalRules);
        return List.copyOf(tenantRules);
    }

    private List<String> applicationCandidates(String applicationAlias) {
        if (MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS.equals(applicationAlias)) {
            return List.of(MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS);
        }
        return List.of(MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS, applicationAlias);
    }

    private MeasureUnitConversionContext normalizeContext(MeasureUnitConversionContext context) {
        if (context == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-conversion-rule.measure-conversion-context-must-not-be-null",
                    "measure conversion context must not be null"));
        }
        String applicationAlias = PlatformNameRules.requireApplicationAlias(context.applicationAlias());
        String moduleAlias = context.moduleAlias() == null || context.moduleAlias().isBlank()
                ? null
                : PlatformNameRules.requireModuleAliasInApplication(context.moduleAlias(), applicationAlias);
        String contextObjectType = context.contextObjectType() == null || context.contextObjectType().isBlank()
                ? null
                : requireCode(context.contextObjectType(), "contextObjectType");
        String contextObjectId = context.contextObjectId() == null || context.contextObjectId().isBlank()
                ? null
                : context.contextObjectId();
        return new MeasureUnitConversionContext(applicationAlias, moduleAlias,
                contextObjectType, contextObjectId, context.operatedAt());
    }

    private void validateNoOverlappingRule(MeasureUnitConversionRule rule) {
        for (MeasureUnitConversionRule existing : list(Criteria.of()
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, rule.getTenantId())
                        .eq("applicationAlias", rule.getApplicationAlias()),
                new PageRequest(0, Integer.MAX_VALUE))) {
            if (Objects.equals(existing.getId(), rule.getId())) {
                continue;
            }
            if (!sameScope(existing, rule) || !overlaps(existing, rule)) {
                continue;
            }
            if (sameDirection(existing, rule)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.measure-unit-conversion-rule.measure-conversion-rule-already-exists-in-overlapping-effective-window",
                        "measure conversion rule already exists in overlapping effective window"));
            }
            if (reverseDirection(existing, rule)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.measure-unit-conversion-rule.measure-conversion-reverse-rule-conflicts-in-overlapping-effective-window",
                        "measure conversion reverse rule conflicts in overlapping effective window"));
            }
        }
    }

    private boolean sameScope(MeasureUnitConversionRule left, MeasureUnitConversionRule right) {
        return left.getScopeType() == right.getScopeType()
                && Objects.equals(left.getModuleAlias(), right.getModuleAlias())
                && Objects.equals(left.getContextObjectType(), right.getContextObjectType())
                && Objects.equals(left.getContextObjectId(), right.getContextObjectId());
    }

    private boolean sameDirection(MeasureUnitConversionRule left, MeasureUnitConversionRule right) {
        return Objects.equals(left.getFromCategoryAlias(), right.getFromCategoryAlias())
                && Objects.equals(left.getFromUnitCode(), right.getFromUnitCode())
                && Objects.equals(left.getToCategoryAlias(), right.getToCategoryAlias())
                && Objects.equals(left.getToUnitCode(), right.getToUnitCode());
    }

    private boolean reverseDirection(MeasureUnitConversionRule left, MeasureUnitConversionRule right) {
        return Objects.equals(left.getFromCategoryAlias(), right.getToCategoryAlias())
                && Objects.equals(left.getFromUnitCode(), right.getToUnitCode())
                && Objects.equals(left.getToCategoryAlias(), right.getFromCategoryAlias())
                && Objects.equals(left.getToUnitCode(), right.getFromUnitCode());
    }

    private boolean overlaps(MeasureUnitConversionRule left, MeasureUnitConversionRule right) {
        return before(left.getEffectiveFrom(), right.getEffectiveTo())
                && before(right.getEffectiveFrom(), left.getEffectiveTo());
    }

    private boolean before(LocalDateTime start, LocalDateTime end) {
        return start == null || end == null || start.isBefore(end);
    }

    private String requireCode(String value, String name) {
        return PlatformNameRules.requireCode(value, name);
    }

    private String requireCategoryAlias(String value, String name) {
        return PlatformNameRules.requireIdentifier(value, name);
    }
}
