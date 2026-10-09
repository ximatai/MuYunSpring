package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.ModuleRecordActionCommand;
import net.ximatai.muyun.spring.common.platform.ModuleRecordActionExecutor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.module.DefaultModuleRecordActionExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowBusinessTaskActionExecutorContractTest {
    @BeforeEach void installMutationHost() { WorkflowTestMutationHost.install(); }
    @AfterEach void resetMutationHost() { WorkflowTestMutationHost.reset(); }

    @Test void sameOrderDefaultRegisteredBeforeDomainDoesNotSwallowStaticBusinessAction() {
        var ability = mock(CrudAbility.class); when(ability.getModuleAlias()).thenReturn("sales.contract");
        @SuppressWarnings("unchecked") ObjectProvider<CrudAbility<?>> abilities = mock(ObjectProvider.class);
        when(abilities.orderedStream()).thenAnswer(call -> Stream.of(ability));
        var records = mock(DynamicRecordService.class);
        var fallback = new DefaultModuleRecordActionExecutor(abilities, records, mock(ActionExecutionPolicyService.class), new ObjectMapper());
        var domain = new DomainActionExecutor();
        var beans = new DefaultListableBeanFactory();
        beans.setDependencyComparator(AnnotationAwareOrderComparator.INSTANCE);
        beans.registerSingleton("defaultExecutor", fallback);
        beans.registerSingleton("domainExecutor", domain);
        var executors = beans.getBeanProvider(ModuleRecordActionExecutor.class);
        assertThat(executors.orderedStream().toList()).containsExactly(fallback, domain);

        var tasks = mock(WorkflowTaskDao.class); var instances = mock(WorkflowInstanceDao.class);
        var runtime = mock(WorkflowModuleTaskRuntimeService.class); var actions = mock(WorkflowTaskActionFacade.class);
        var task = new WorkflowTask(); task.setId("task"); task.setInstanceId("instance");
        when(tasks.findById("task")).thenReturn(task);
        var instance = new WorkflowInstance(); instance.setId("instance"); instance.setModuleAlias("sales.contract"); instance.setRecordId("record");
        when(instances.findById("instance")).thenReturn(instance);
        var guide = new WorkflowTaskGuide(); guide.setGuideKey("deliver"); guide.setGuideKind(WorkflowTaskGuideKind.EXECUTE_ACTION); guide.setTargetActionCode("deliver");
        when(runtime.prepare("task", "operator")).thenReturn(new WorkflowModuleTaskProcessBundle(
                "task", "instance", "visit", "sales.contract", "record", null, null, null,
                WorkflowModuleTaskEvaluation.manualConfirm(List.of(guide)), guide));
        var service = new WorkflowBusinessTaskActionService(tasks, instances, runtime, actions, executors);

        var result = service.execute("task", "deliver", null, Map.of(), Map.of(), "operator", "完成交付");

        assertThat(result.businessResult()).isEqualTo("delivered");
        assertThat(domain.command).isEqualTo(new ModuleRecordActionCommand("sales.contract", "record", "deliver", null, Map.of(), Map.of()));
        verify(actions).execute(eq("complete"), argThat(request -> "task".equals(request.taskId())));
        verifyNoInteractions(records);
        verify(ability, never()).update(any());
    }

    private static final class DomainActionExecutor implements ModuleRecordActionExecutor, Ordered {
        private ModuleRecordActionCommand command;
        @Override public int getOrder() { return LOWEST_PRECEDENCE; }
        @Override public boolean supports(String moduleAlias, String code) {
            return "sales.contract".equals(moduleAlias) && "deliver".equals(code);
        }
        @Override public Object execute(ModuleRecordActionCommand command) { this.command = command; return "delivered"; }
    }
}
