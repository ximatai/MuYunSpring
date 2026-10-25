package net.ximatai.muyun.spring.platform.web.job;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.job.JobPlan;
import net.ximatai.muyun.spring.platform.job.JobPlanManagementService;
import net.ximatai.muyun.spring.platform.job.JobRun;
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
        alias = JobPlanManagementService.MODULE_ALIAS, title = "作业计划")
@StaticModuleTenantScopePolicy(requireActiveTenant = false)
@PlatformMenu(parent = PlatformMenuGroups.OPS, title = "作业计划", order = 40)
@RequestMapping("/platform.job_plan")
public class JobPlanWebController extends StaticModuleWebControllerAdapter<JobPlanManagementService>
        implements CrudWeb<JobPlan, JobPlanManagementService>, StaticModuleUiContributor {
    private final JobService jobs;

    public JobPlanWebController(JobService jobs) {
        this.jobs = Objects.requireNonNull(jobs);
    }

    @PostMapping("/{id}/run")
    @CustomActionEndpoint(value = "runNow", title = "立即执行", level = PlatformActionLevel.RECORD,
            pageInvocable = true)
    public JobRun runNow(@PathVariable String id) {
        return webScope(() -> jobs.runNow(id));
    }

    @PostMapping("/{id}/pause")
    @CustomActionEndpoint(value = "pause", title = "暂停", level = PlatformActionLevel.RECORD,
            pageInvocable = true)
    public void pause(@PathVariable String id) {
        webScope(() -> { jobs.pause(id); return null; });
    }

    @PostMapping("/{id}/resume")
    @CustomActionEndpoint(value = "resume", title = "恢复", level = PlatformActionLevel.RECORD,
            pageInvocable = true)
    public void resume(@PathVariable String id) {
        webScope(() -> { jobs.resume(id); return null; });
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        UiFormula editable = UiFormula.booleanExpression("!(PRESENT({managedBy}))");
        return ModuleUiDefinition.builder(JobPlanManagementService.MODULE_ALIAS)
                .pageAction("runNow", PageActionAnchor.DETAIL)
                .pageAction("pause", PageActionAnchor.DETAIL)
                .pageAction("resume", PageActionAnchor.DETAIL)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields
                                .field("tenantId", field -> field.label("所属租户 ID").readOnly())
                                .field("jobType", field -> field.label("作业类型"))
                                .field("scope", field -> field.label("作用范围"))
                                .field("enabled", field -> field.label("启用状态"))
                                .field("available", field -> field.label("类型可用"))
                                .field("nextFireAt", field -> field.label("下次触发时间"))
                                .field("lastStatus", field -> field.label("最近运行状态"))
                                .field("consecutiveFailures", field -> field.label("连续失败次数"))))
                        .detail(detail -> detail.editor(form -> form.title("作业计划")
                                .field("tenantId", field -> field.label("所属租户 ID").readOnly())
                                .field("jobType", field -> field.label("作业类型").readOnly())
                                .field("scope", field -> field.label("作用范围").readOnly())
                                .field("objectKey", field -> field.label("业务对象键").readOnly())
                                .field("managedBy", field -> field.label("托管方").readOnly())
                                .field("identityMode", field -> field.label("执行身份").readOnly())
                                .field("scheduleKind", field -> field.label("计划类型").enabledWhen(editable))
                                .field("expression", field -> field.label("Cron 表达式").enabledWhen(editable))
                                .field("zoneId", field -> field.label("时区").enabledWhen(editable))
                                .field("intervalMillis", field -> field.label("固定间隔（毫秒）").enabledWhen(editable))
                                .field("initialDelayMillis", field -> field.label("首次延迟（毫秒）").enabledWhen(editable))
                                .field("nextFireAt", field -> field.label("下次触发时间").readOnly())
                                .field("lastStatus", field -> field.label("最近运行状态").readOnly())
                                .field("consecutiveFailures", field -> field.label("连续失败次数").readOnly())
                                .field("skippedCount", field -> field.label("跳过次数").readOnly())))))
                .build();
    }
}
