package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import javax.sql.DataSource;
import java.util.Map;
import static org.mockito.Mockito.mock;

/** Shared repository wiring; concurrency probes are explicitly opted into by their scenarios. */
@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableMuYunRepositories(basePackageClasses = WorkflowInstanceDao.class)
@Import({WorkflowDefinitionService.class, WorkflowVersionService.class, WorkflowDefinitionSelector.class,
            WorkflowConditionService.class, WorkflowInstanceService.class, WorkflowInstanceStateService.class,
            WorkflowNodeInstanceStateService.class, WorkflowRouteInstanceStateService.class, WorkflowRouteRuntimeService.class,
            WorkflowRuntimeEventFactory.class, WorkflowInstanceSnapshotFactory.class, WorkflowParticipantService.class,
            WorkflowRuntimeTaskFactory.class, WorkflowSubmitDraftService.class, WorkflowRuntimeSubmitService.class,
            WorkflowSubmitFacade.class, WorkflowRuntimeActivationService.class, WorkflowRuntimeProgressionService.class,
            WorkflowTaskActionService.class, WorkflowAutomaticApprovalService.class, WorkflowActionPolicyService.class, WorkflowTaskAssignmentPolicyService.class,
            WorkflowApprovalTaskPolicyService.class, WorkflowDelegationService.class, WorkflowDelegationCompletionNoticeService.class,
            WorkflowRuntimePluginDispatcher.class, WorkflowArchiveService.class})
class WorkflowRepositoryTestConfiguration extends PlatformPostgresIntegrationTest {
    @Bean WorkflowSubmitActionPolicyResolver submissionPolicies() { return WorkflowTestSupport.submissionPolicies(); }
    @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
    @Bean ModuleRecordFacts facts() { return (module, id) -> Map.of("id", id); }
    @Bean WorkflowApprovalSummaryWriter summary() { return mock(WorkflowApprovalSummaryWriter.class); }
    @Bean WorkflowModuleTaskEvaluator evaluator() { return mock(WorkflowModuleTaskEvaluator.class); }
    @Bean WorkflowBusinessTaskResolver specifications() { return mock(WorkflowBusinessTaskResolver.class); }
}
