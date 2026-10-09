package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import java.util.List;
import java.util.Map;

/** Default business facts for workflow unit fixtures that only exercise routing. */
public final class WorkflowTestSupport {
    private WorkflowTestSupport() {}
    public static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("workflowFixture", value);
        @SuppressWarnings("unchecked") Class<T> type = (Class<T>) value.getClass();
        return factory.getBeanProvider(type);
    }
    public static ModuleRecordFacts facts() { return (moduleAlias, recordId) -> Map.of("id", recordId); }
    public static WorkflowParticipantService participants() { return new WorkflowParticipantService(List.of(), facts()); }
}
