package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ModuleRecordActionExecutor;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutorRegistry;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationRuleService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateService;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskCheckBlock;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/** Publication resolves authored references against existing executable module catalogs. */
@Service
public class WorkflowBusinessTaskReferenceValidator {
    private final PlatformModuleService modules;
    private final PlatformModuleActionService actions;
    private final PlatformQueryTemplateService templates;
    private final PlatformQueryItemService queries;
    private final RecordGenerationRuleService generations;
    private final DynamicRecordService records;
    private final ObjectProvider<ModuleRecordActionExecutor> executors;
    private final DynamicActionExecutorRegistry dynamicExecutors;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowBusinessTaskReferenceValidator(PlatformModuleService modules, PlatformModuleActionService actions,
            PlatformQueryTemplateService templates, PlatformQueryItemService queries,
            RecordGenerationRuleService generations, DynamicRecordService records,
            ObjectProvider<ModuleRecordActionExecutor> executors, DynamicRecordRuntime runtime) {
        this.modules = Objects.requireNonNull(modules); this.actions = Objects.requireNonNull(actions);
        this.templates = Objects.requireNonNull(templates); this.queries = Objects.requireNonNull(queries);
        this.generations = Objects.requireNonNull(generations); this.records = Objects.requireNonNull(records);
        this.executors = Objects.requireNonNull(executors);
        this.dynamicExecutors = Objects.requireNonNull(runtime).actionExecutorRegistry();
    }

    public void check(String moduleAlias, WorkflowTaskCheck check) {
        if (check.getCheckKind() == WorkflowTaskCheckKind.FORMULA || check.getCheckKind() == WorkflowTaskCheckKind.MANUAL_CONFIRM) return;
        final PlatformTaskCheckBlock block;
        try { block = mapper.readValue(check.getCheckConfigText(), PlatformTaskCheckBlock.class); }
        catch (Exception invalid) { throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-reference-validator.invalid-task-check-configuration",
                "任务检查配置无效"), Map.of(), invalid); }
        switch (block.checkType()) {
            case QUERY_TEMPLATE -> {
                var template = templates.select(block.queryTemplateId());
                requireOwned(template, "查询模板");
                if (!Boolean.TRUE.equals(template.getEnabled()) || !Boolean.TRUE.equals(template.getPublished())
                       ) throw PlatformErrors.badRequestText(TextReference.code(
                               "platform.rejection.workflow-business-task-reference-validator.the-task-query-template-is-unpublished-or-disabled",
                               "任务查询模板未发布或已停用"));
                requireModule(template.getModuleAlias());
                if (block.externalRecordIdKey() != null && !queries.externalValueKeys(template.getId()).contains(block.externalRecordIdKey()))
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-business-task-reference-validator.the-task-query-template-has-no-record-binding-parameter",
                            "任务查询模板未声明记录绑定参数: {value1}"), Map.of("value1", String.valueOf(block.externalRecordIdKey())));
                queries.compile(template.getId(), block.externalRecordIdKey() == null ? Map.of()
                        : Map.of(block.externalRecordIdKey(), "workflow-publication-record"));
            }
            case ASSOCIATION_VIEW -> {
                if (requireModule(moduleAlias).getModuleKind() != ModuleKind.DYNAMIC)
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-business-task-reference-validator.association-checks-require-a-module-with-dynamic-association-views",
                            "关联视图检查要求模块已声明动态关联视图"));
                var view = records.associationView(moduleAlias, records.mainEntityAlias(moduleAlias), block.associationViewCode());
                if (view == null || !view.queryable()) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-reference-validator.task-association-view-not-found-or-not-queryable",
                        "任务关联视图不存在或不可查询: {value1}"), Map.of("value1", String.valueOf(block.associationViewCode())));
                requireModule(view.targetModuleAlias());
            }
            case GENERATED_RELATION -> {
                requireModule(block.targetModuleAlias());
                if (block.generationRuleId() != null) {
                    var rule = generations.select(block.generationRuleId());
                    requireOwned(rule, "生成规则");
                    if (!Boolean.TRUE.equals(rule.getEnabled()) || !moduleAlias.equals(rule.getSourceModuleAlias())
                            || !block.targetModuleAlias().equals(rule.getTargetModuleAlias()))
                        throw PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.workflow-business-task-reference-validator.the-generation-rule-is-disabled-or-does-not-match-the-source-and",
                                "任务生成规则已停用或不属于声明的来源和目标模块"));
                }
            }
            default -> throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-reference-validator.unsupported-task-check-reference",
                    "不支持的任务检查引用"));
        }
    }

    public void guide(String moduleAlias, WorkflowTaskGuide guide) {
        if (guide.getGuideKind() == WorkflowTaskGuideKind.READ_INSTRUCTION) return;
        String target = guide.getTargetModuleAlias() == null ? moduleAlias : guide.getTargetModuleAlias();
        var module = requireModule(target);
        if (guide.getGuideKind() != WorkflowTaskGuideKind.OPEN_FORM && guide.getGuideKind() != WorkflowTaskGuideKind.EXECUTE_ACTION) return;
        String code = guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM ? "update" : guide.getTargetActionCode();
        var action = actions.findByModuleAliasAndActionCode(target, code);
        if (action == null || Boolean.TRUE.equals(action.getDeleted()) || !Boolean.TRUE.equals(action.getEnabled())
                || !target.equals(action.getModuleAlias()) || !code.equals(action.getActionCode()) || action.getTenantId() != null)
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-reference-validator.task-action-not-found-or-disabled",
                    "任务业务动作不存在或已停用: {value1}.{value2}"), Map.of("value1", String.valueOf(target), "value2", String.valueOf(code)));
        if (action.getActionLevel() != EntityActionLevel.RECORD && action.getActionLevel() != EntityActionLevel.ANY)
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-reference-validator.the-task-action-must-support-processing-the-current-record",
                    "任务业务动作必须支持当前记录办理: {value1}"), Map.of("value1", String.valueOf(code)));
        executors.orderedStream().filter(executor -> executor.supports(target, code)).findFirst()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-reference-validator.the-task-action-has-no-executor",
                        "任务业务动作缺少执行器: {value1}.{value2}"), Map.of("value1", String.valueOf(target), "value2", String.valueOf(code))));
        if ("update".equals(code)) return;
        if (module.getModuleKind() == ModuleKind.DYNAMIC) {
            var descriptor = records.action(target, code);
            if (descriptor == null || !descriptor.enabled() || descriptor.actionLevel() != EntityActionLevel.RECORD && descriptor.actionLevel() != EntityActionLevel.ANY
                    || !Objects.equals(records.mainEntityAlias(target), records.actionEntityAlias(target, code))
                    || descriptor.executorType() != EntityActionExecutorType.SERVICE && descriptor.executorType() != EntityActionExecutorType.GENERATE
                    || !dynamicExecutors.contains(descriptor.executorKey()))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-reference-validator.the-task-action-is-not-bound-to-the-current-record-s-service",
                        "任务业务动作未绑定当前主记录的服务执行器: {value1}"), Map.of("value1", String.valueOf(code)));
        }
    }

    private PlatformModule requireModule(String alias) {
        var module = modules.resolveVisibleModule(alias);
        if (module == null || !alias.equals(module.getAlias()) || Boolean.TRUE.equals(module.getDeleted()) || !Boolean.TRUE.equals(module.getEnabled()))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-reference-validator.the-task-s-business-module-was-not-found-or-is-disabled",
                    "任务引用的业务模块不存在或已停用: {value1}"), Map.of("value1", String.valueOf(alias)));
        return module;
    }

    private void requireOwned(EntityContract entity, String name) {
        if (entity == null || Boolean.TRUE.equals(entity.getDeleted()) || !TenantContext.tenantFilterBypassed()
                && !Objects.equals(entity.getTenantId(), TenantContext.currentTenantId().orElse(null)))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-reference-validator.the-task-reference-was-not-found-or-does-not-belong-to-the",
                    "任务{value1}不存在或不属于当前租户"), Map.of("value1", String.valueOf(name)));
    }
}
