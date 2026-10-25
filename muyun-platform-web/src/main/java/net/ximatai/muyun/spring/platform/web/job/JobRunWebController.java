package net.ximatai.muyun.spring.platform.web.job;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.job.JobRun;
import net.ximatai.muyun.spring.platform.job.JobRunManagementService;
import net.ximatai.muyun.spring.platform.job.JobService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.*;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@PlatformStaticModule(application = PlatformApplication.class,
        alias = JobRunManagementService.MODULE_ALIAS, title = "作业运行")
@StaticModuleTenantScopePolicy(requireActiveTenant = false)
@PlatformMenu(parent = PlatformMenuGroups.OPS, title = "作业运行", order = 50)
@RequestMapping("/platform.job_run")
public class JobRunWebController extends StaticModuleWebControllerAdapter<JobRunManagementService>
        implements StaticQueryViewWeb<JobRun, JobRunManagementService>, StaticModuleUiContributor {
    private final JobService jobs;

    public JobRunWebController(JobService jobs) {
        this.jobs = Objects.requireNonNull(jobs);
    }

    @PostMapping("/{id}/cancel")
    @CustomActionEndpoint(value = "cancel", title = "取消运行", level = PlatformActionLevel.RECORD,
            pageInvocable = true)
    public void cancel(@PathVariable String id) {
        webScope(() -> { jobs.cancel(id); return null; });
    }

    @PostMapping("/{id}/retry")
    @CustomActionEndpoint(value = "retry", title = "重试失败运行", level = PlatformActionLevel.RECORD,
            pageInvocable = true)
    public JobRun retry(@PathVariable String id) {
        return webScope(() -> jobs.retry(id));
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(JobRunManagementService.MODULE_ALIAS)
                .pageAction("cancel", PageActionAnchor.DETAIL)
                .pageAction("retry", PageActionAnchor.DETAIL)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields
                                .field("tenantId", field -> field.label("所属租户 ID").readOnly())
                                .field("jobType", field -> field.label("作业类型"))
                                .field("status", field -> field.label("运行状态"))
                                .field("triggerType", field -> field.label("触发方式"))
                                .field("attempts", field -> field.label("尝试次数"))
                                .field("progress", field -> field.label("进度"))
                                .field("startedAt", field -> field.label("开始时间"))
                                .field("finishedAt", field -> field.label("结束时间"))))
                        .detail(detail -> detail.display(form -> form.title("作业运行")
                                .field("tenantId", field -> field.label("所属租户 ID").readOnly())
                                .field("jobType", field -> field.label("作业类型"))
                                .field("planId", field -> field.label("所属计划"))
                                .field("planRevision", field -> field.label("计划修订"))
                                .field("scheduledAt", field -> field.label("计划触发时刻"))
                                .field("triggerType", field -> field.label("触发方式"))
                                .field("triggeredBy", field -> field.label("触发人"))
                                .field("identityMode", field -> field.label("执行身份"))
                                .field("status", field -> field.label("运行状态"))
                                .field("attempts", field -> field.label("尝试次数"))
                                .field("progress", field -> field.label("进度"))
                                .field("resultCode", field -> field.label("结果码"))
                                .field("statisticsJson", field -> field.label("统计摘要"))
                                .field("errorSummary", field -> field.label("错误摘要"))
                                .field("traceId", field -> field.label("追踪编号"))))))
                .build();
    }
}
