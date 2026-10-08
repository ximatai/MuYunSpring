package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.CrudAbility;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.ModuleRecordActionExecutor;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationRule;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationRuleService;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.platform.ui.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowBusinessTaskReferenceValidatorTest {
    private final String moduleAlias = "sales.contract";
    private final PlatformModuleService modules = mock(PlatformModuleService.class);
    private final PlatformModuleActionService actions = mock(PlatformModuleActionService.class);
    private final PlatformQueryTemplateService templates = mock(PlatformQueryTemplateService.class);
    private final PlatformQueryItemService queries = mock(PlatformQueryItemService.class);
    private final RecordGenerationRuleService generations = mock(RecordGenerationRuleService.class);
    private final DynamicRecordService records = mock(DynamicRecordService.class);
    private final DynamicRecordRuntime runtime = mock(DynamicRecordRuntime.class);
    private final DynamicActionExecutorRegistry dynamicExecutors = mock(DynamicActionExecutorRegistry.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<ModuleRecordActionExecutor> executors = mock(ObjectProvider.class);
    private final WorkflowBusinessTaskReferenceValidator validator;

    WorkflowBusinessTaskReferenceValidatorTest() {
        when(runtime.actionExecutorRegistry()).thenReturn(dynamicExecutors);
        when(executors.orderedStream()).thenAnswer(call -> Stream.empty());
        validator = new WorkflowBusinessTaskReferenceValidator(modules, actions, templates, queries, generations, records, executors, runtime);
    }

    @Test void missingUnpublishedDisabledOrForeignQueryTemplatesCannotFreeze() {
        var check = query();
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).isInstanceOf(PlatformException.class).hasMessageContaining("查询模板");
        var template = template(); when(templates.select("query")).thenReturn(template);
        template.setPublished(false);
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("未发布");
        template.setPublished(true); template.setEnabled(false);
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("已停用");
        template.setEnabled(true); template.setTenantId("tenant-b");
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("当前租户");
        }
        verifyNoInteractions(queries);
    }

    @Test void publishedQueryChecksCompileAgainstDeclaredRecordBinding() {
        module(moduleAlias, ModuleKind.STATIC); var template = template(); when(templates.select("query")).thenReturn(template);
        var check = query(); check.setCheckConfigText("{\"checkType\":\"QUERY_TEMPLATE\",\"queryTemplateId\":\"query\",\"externalRecordIdKey\":\"recordId\"}");
        when(queries.externalValueKeys("query")).thenReturn(List.of());
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("记录绑定参数");
        when(queries.externalValueKeys("query")).thenReturn(List.of("recordId"));
        validator.check(moduleAlias, check);
        verify(queries).compile("query", Map.of("recordId", "workflow-publication-record"));
    }

    @Test void readonlyQueryChecksMayUsePublishedTemplatesOwnedByAnotherVisibleModule() {
        module("sales.other", ModuleKind.STATIC);
        var template = template(); template.setModuleAlias("sales.other"); when(templates.select("query")).thenReturn(template);
        assertThatCode(() -> validator.check(moduleAlias, query())).doesNotThrowAnyException();
        verify(queries).compile("query", Map.of());
    }

    @Test void queryCompilationErrorsArePublicationErrorsRatherThanFutureTaskFailures() {
        module(moduleAlias, ModuleKind.STATIC); when(templates.select("query")).thenReturn(template());
        doThrow(new PlatformException("query field no longer exists")).when(queries).compile("query", Map.of());
        assertThatThrownBy(() -> validator.check(moduleAlias, query())).hasMessageContaining("no longer exists");
    }

    @Test void nonexistentDisabledAndListActionsCannotFreeze() {
        module(moduleAlias, ModuleKind.STATIC); var guide = guide("deliver");
        assertThatThrownBy(() -> validator.guide(moduleAlias, guide)).hasMessageContaining("不存在");
        var action = action("deliver"); action.setEnabled(false);
        assertThatThrownBy(() -> validator.guide(moduleAlias, guide)).hasMessageContaining("已停用");
        action.setEnabled(true);
        for (var level : List.of(EntityActionLevel.LIST, EntityActionLevel.BATCH)) {
            action.setActionLevel(level);
            assertThatThrownBy(() -> validator.guide(moduleAlias, guide)).hasMessageContaining("当前记录");
        }
    }

    @Test void defaultDispatcherDoesNotProveThatAStaticCustomActionIsExecutable() {
        module(moduleAlias, ModuleKind.STATIC); action("deliver");
        var ability = mock(CrudAbility.class); when(ability.getModuleAlias()).thenReturn(moduleAlias);
        @SuppressWarnings("unchecked") ObjectProvider<CrudAbility<?>> abilities = mock(ObjectProvider.class);
        when(abilities.orderedStream()).thenAnswer(call -> Stream.of(ability));
        var fallback = new DefaultModuleRecordActionExecutor(abilities, records, mock(ActionExecutionPolicyService.class), new ObjectMapper());
        when(executors.orderedStream()).thenAnswer(call -> Stream.of(fallback));
        assertThatThrownBy(() -> validator.guide(moduleAlias, guide("deliver"))).hasMessageContaining("缺少执行器");
        var domain = mock(ModuleRecordActionExecutor.class); when(domain.supports(moduleAlias, "deliver")).thenReturn(true);
        when(executors.orderedStream()).thenAnswer(call -> Stream.of(fallback, domain));
        assertThatCode(() -> validator.guide(moduleAlias, guide("deliver"))).doesNotThrowAnyException();
    }

    @Test void standardUpdateNeedsPublishedActionAndAnActualExecutor() {
        module(moduleAlias, ModuleKind.STATIC); action("update");
        assertThatThrownBy(() -> validator.guide(moduleAlias, guide("update"))).hasMessageContaining("缺少执行器");
        var executor = mock(ModuleRecordActionExecutor.class); when(executor.supports(moduleAlias, "update")).thenReturn(true);
        when(executors.orderedStream()).thenAnswer(call -> Stream.of(executor));
        assertThatCode(() -> validator.guide(moduleAlias, guide("update"))).doesNotThrowAnyException();
        verifyNoInteractions(records);
    }

    @Test void dynamicBusinessActionsRequireMainRecordServiceBindingAndDeployedExecutor() {
        module(moduleAlias, ModuleKind.DYNAMIC); action("deliver");
        var executor = mock(ModuleRecordActionExecutor.class); when(executor.supports(moduleAlias, "deliver")).thenReturn(true);
        when(executors.orderedStream()).thenAnswer(call -> Stream.of(executor));
        when(records.mainEntityAlias(moduleAlias)).thenReturn("main");
        when(records.actionEntityAlias(moduleAlias, "deliver")).thenReturn("child");
        when(records.action(moduleAlias, "deliver")).thenReturn(descriptor(EntityActionExecutorType.SERVICE));
        when(dynamicExecutors.contains("executor")).thenReturn(true);
        assertThatThrownBy(() -> validator.guide(moduleAlias, guide("deliver"))).hasMessageContaining("当前主记录");
        when(records.actionEntityAlias(moduleAlias, "deliver")).thenReturn("main");
        for (var type : List.of(EntityActionExecutorType.DIALOG, EntityActionExecutorType.WORKFLOW, EntityActionExecutorType.STANDARD)) {
            when(records.action(moduleAlias, "deliver")).thenReturn(descriptor(type));
            assertThatThrownBy(() -> validator.guide(moduleAlias, guide("deliver"))).hasMessageContaining("服务执行器");
        }
        when(records.action(moduleAlias, "deliver")).thenReturn(descriptor(EntityActionExecutorType.SERVICE));
        when(dynamicExecutors.contains("executor")).thenReturn(false);
        assertThatThrownBy(() -> validator.guide(moduleAlias, guide("deliver"))).hasMessageContaining("服务执行器");
        when(dynamicExecutors.contains("executor")).thenReturn(true);
        assertThatCode(() -> validator.guide(moduleAlias, guide("deliver"))).doesNotThrowAnyException();
        var directoryAction = action("deliver"); directoryAction.setActionLevel(EntityActionLevel.ANY);
        var general = new DynamicActionDescriptor("deliver", "Deliver", true, EntityActionLevel.ANY, EntityActionCategory.CUSTOM,
                EntityActionAccessMode.AUTH_REQUIRED, true, true, null, false, null, EntityActionExecutorType.SERVICE, "executor");
        when(records.action(moduleAlias, "deliver")).thenReturn(general);
        assertThatCode(() -> validator.guide(moduleAlias, guide("deliver"))).doesNotThrowAnyException();
    }

    @Test void associationChecksRequireQueryableMainViewAndActiveTargetModule() {
        module(moduleAlias, ModuleKind.DYNAMIC); when(records.mainEntityAlias(moduleAlias)).thenReturn("main");
        var check = check(WorkflowTaskCheckKind.RELATED_QUERY_EXISTS, "{\"checkType\":\"ASSOCIATION_VIEW\",\"associationViewCode\":\"related\"}");
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("关联视图");
        when(records.associationView(moduleAlias, "main", "related")).thenReturn(new DynamicAssociationViewDescriptor(
                "related", "main", "sales.target", "main", null, null, null, null, false));
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("不可查询");
        when(records.associationView(moduleAlias, "main", "related")).thenReturn(new DynamicAssociationViewDescriptor(
                "related", "main", "sales.target", "main", null, null, null, null, true));
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("业务模块");
        module("sales.target", ModuleKind.DYNAMIC);
        assertThatCode(() -> validator.check(moduleAlias, check)).doesNotThrowAnyException();
    }

    @Test void generationChecksCannotUseDisabledForeignOrMismatchedRules() {
        module("sales.target", ModuleKind.STATIC);
        var check = check(WorkflowTaskCheckKind.GENERATED_QUERY_EXISTS, "{\"checkType\":\"GENERATED_RELATION\",\"targetModuleAlias\":\"sales.target\",\"generationRuleId\":\"rule\"}");
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("生成规则");
        var rule = new RecordGenerationRule(); rule.setSourceModuleAlias("sales.other"); rule.setTargetModuleAlias("sales.target"); rule.setEnabled(true);
        when(generations.select("rule")).thenReturn(rule);
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("来源和目标模块");
        rule.setSourceModuleAlias(moduleAlias); rule.setEnabled(false);
        assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("已停用");
        rule.setEnabled(true); rule.setTenantId("tenant-b");
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> validator.check(moduleAlias, check)).hasMessageContaining("当前租户");
        }
        rule.setTenantId(null);
        assertThatCode(() -> validator.check(moduleAlias, check)).doesNotThrowAnyException();
    }

    @Test void readonlyGuidesMustResolveTheirNavigationTarget() {
        var guide = new WorkflowTaskGuide(); guide.setGuideKind(WorkflowTaskGuideKind.OPEN_LIST); guide.setTargetModuleAlias("sales.target");
        assertThatThrownBy(() -> validator.guide(moduleAlias, guide)).hasMessageContaining("业务模块");
        module("sales.target", ModuleKind.STATIC);
        assertThatCode(() -> validator.guide(moduleAlias, guide)).doesNotThrowAnyException();
    }

    private PlatformModule module(String alias, ModuleKind kind) {
        var module = new PlatformModule(); module.setAlias(alias); module.setEnabled(true); module.setModuleKind(kind);
        when(modules.resolveVisibleModule(alias)).thenReturn(module); return module;
    }
    private PlatformModuleAction action(String code) {
        var action = new PlatformModuleAction(); action.setModuleAlias(moduleAlias); action.setActionCode(code);
        action.setEnabled(true); action.setActionLevel(EntityActionLevel.RECORD);
        when(actions.findByModuleAliasAndActionCode(moduleAlias, code)).thenReturn(action); return action;
    }
    private PlatformQueryTemplate template() {
        var template = new PlatformQueryTemplate(); template.setId("query"); template.setModuleAlias(moduleAlias); template.setEnabled(true); template.setPublished(true); return template;
    }
    private WorkflowTaskGuide guide(String code) {
        var guide = new WorkflowTaskGuide(); guide.setGuideKind(WorkflowTaskGuideKind.EXECUTE_ACTION); guide.setTargetActionCode(code); return guide;
    }
    private WorkflowTaskCheck query() { return check(WorkflowTaskCheckKind.QUERY_EXISTS, "{\"checkType\":\"QUERY_TEMPLATE\",\"queryTemplateId\":\"query\"}"); }
    private WorkflowTaskCheck check(WorkflowTaskCheckKind kind, String config) {
        var check = new WorkflowTaskCheck(); check.setCheckKind(kind); check.setCheckConfigText(config); return check;
    }
    private DynamicActionDescriptor descriptor(EntityActionExecutorType type) {
        return new DynamicActionDescriptor("deliver", "Deliver", true, EntityActionLevel.RECORD, EntityActionCategory.CUSTOM,
                EntityActionAccessMode.AUTH_REQUIRED, true, true, null, false, null, type, "executor");
    }
}
