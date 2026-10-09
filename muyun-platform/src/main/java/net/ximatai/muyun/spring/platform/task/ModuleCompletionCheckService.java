package net.ximatai.muyun.spring.platform.task;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.ui.*;
import net.ximatai.muyun.spring.platform.impact.RecordImpactRelationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

/** Live business completion checks shared by page completion items and workflow tasks. */
@Service
public class ModuleCompletionCheckService {
    private final DynamicRecordService records;
    private final PlatformQueryItemService queries;
    private final PlatformQueryTemplateService templates;
    private final Optional<RecordImpactRelationService> relations;
    private final ObjectProvider<CrudAbility<?>> abilities;
    private final ModuleRecordFacts facts;
    private final FormulaEngine formulas = new FormulaEngine();

    public ModuleCompletionCheckService(DynamicRecordService records, PlatformQueryItemService queries,
            PlatformQueryTemplateService templates, Optional<RecordImpactRelationService> relations,
            ObjectProvider<CrudAbility<?>> abilities, ModuleRecordFacts facts) {
        this.records = records; this.queries = queries; this.templates = templates; this.relations = relations;
        this.abilities = abilities; this.facts = facts;
    }

    public boolean formula(String moduleAlias, String recordId, String expression) {
        if (expression == null || expression.isBlank()) throw new PlatformException("完成项检查公式不能为空");
        if (!formulas.assignedFields(expression).isEmpty()) throw new PlatformException("完成项检查不能修改业务字段");
        Object result = formulas.evaluateValue(expression, FormulaRuntimeData.of(facts.read(moduleAlias, recordId)));
        if (result instanceof Boolean passed) return passed;
        throw new PlatformException("完成项检查公式必须返回布尔值，请使用比较公式，例如 {amount} > 100");
    }

    public PlatformModuleTaskCheckDetail check(String moduleAlias, String recordId, PlatformTaskCheckBlock check) {
        PlatformQueryTemplate template = null;
        if (check.checkType() == PlatformTaskCheckType.QUERY_TEMPLATE) {
            template = templates.select(check.queryTemplateId());
            if (template == null || !Boolean.TRUE.equals(template.getEnabled()) || !Boolean.TRUE.equals(template.getPublished()))
                throw new PlatformException("完成项查询模板不可用: " + check.queryTemplateId());
        }
        return check(moduleAlias, recordId, check, template);
    }

    /** Uses a template already resolved from the caller's published configuration boundary. */
    public PlatformModuleTaskCheckDetail check(String moduleAlias, String recordId, PlatformTaskCheckBlock check,
                                               PlatformQueryTemplate verifiedTemplate) {
        if (check.checkType() == PlatformTaskCheckType.MANUAL)
            return new PlatformModuleTaskCheckDetail(check.checkType(), null, null, check.expectedCount(), check.diagnosticPath(), "请人工确认");
        long count;
        switch (check.checkType()) {
            case ASSOCIATION_VIEW -> count = records.associationViewPage(moduleAlias, records.mainEntityAlias(moduleAlias), recordId,
                    check.associationViewCode(), Criteria.of(), PageRequest.of(1, 1)).getTotal();
            case QUERY_TEMPLATE -> {
                var template = verifiedTemplate;
                if (template == null || !java.util.Objects.equals(check.queryTemplateId(), template.getId()))
                    throw new PlatformException("完成项查询模板与已验证配置不一致: " + check.queryTemplateId());
                Map<String, Object> values = new LinkedHashMap<>();
                if (check.externalRecordIdKey() != null) values.put(check.externalRecordIdKey(), recordId);
                count = count(template.getModuleAlias(), queries.compile(template.getId(), values));
            }
            case GENERATED_RELATION -> {
                var service = relations.orElseThrow(() -> new PlatformException("生成关系检查能力未安装"));
                var seen = new LinkedHashSet<String>();
                long visible = 0;
                int page = 1;
                while (visible < check.expectedCount()) {
                    var targets = service.listGeneratedTargets(moduleAlias, recordId, check.targetModuleAlias(),
                            check.generationRuleId(), PageRequest.of(page++, 100));
                    if (targets.isEmpty()) break;
                    var ids = targets.stream().map(item -> item.getTargetRecordId()).filter(java.util.Objects::nonNull)
                            .filter(seen::add).toList();
                    if (!ids.isEmpty()) visible += count(check.targetModuleAlias(), Criteria.of().in("id", ids));
                    if (targets.size() < 100) break;
                }
                count = visible;
            }
            default -> throw new PlatformException("unsupported completion check: " + check.checkType());
        }
        return new PlatformModuleTaskCheckDetail(check.checkType(), count >= check.expectedCount(), count,
                check.expectedCount(), check.diagnosticPath(), null);
    }

    private long count(String moduleAlias, Criteria criteria) {
        var ability = abilities.orderedStream().filter(item -> moduleAlias.equals(item.getModuleAlias())).findFirst();
        if (ability.isPresent()) {
            var target = ability.get();
            return target instanceof DataScopeAbility<?> scoped
                    ? scoped.countForAction(PlatformAction.QUERY, criteria) : target.count(criteria);
        }
        return records.count(moduleAlias, records.mainEntityAlias(moduleAlias), criteria);
    }
}
