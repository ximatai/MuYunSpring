package net.ximatai.muyun.spring.platform.web.job;

import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.*;
import org.springframework.web.bind.annotation.*;

@RestController
@PlatformStaticModule(application = PlatformApplication.class, alias = ScheduledActionPlanService.MODULE_ALIAS, title = "定时动作计划")
@StaticModuleTenantScopePolicy(requireActiveTenant = false)
@PlatformMenu(parent = PlatformMenuGroups.OPS, title = "定时动作计划", order = 42)
@RequestMapping("/platform.scheduled_action_plan")
public class ScheduledActionPlanWebController extends StaticModuleWebControllerAdapter<ScheduledActionPlanService>
        implements CrudWeb<ScheduledActionPlan, ScheduledActionPlanService>, StaticModuleUiContributor {
    @Override public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(ScheduledActionPlanService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page.quickSearch("moduleAlias", "actionCode")
                        .explorer(explorer -> explorer.title("定时动作计划").titleField("moduleAlias").secondaryField("actionCode"))
                        .detail(detail -> detail.editor(form -> form.title("定时动作计划")
                                .field("tenantId", field -> field.label("业务执行租户（平台范围留空）"))
                                .field("moduleAlias", field -> field.label("目标模块").required())
                                .field("actionCode", field -> field.label("动作编码").required())
                                .field("argumentsJson", field -> field.label("静态参数 JSON（禁止凭据）").uiType("textarea").required())
                                .field("identityMode", field -> field.label("执行身份（INITIATOR / SYSTEM）").required())
                                .field("executionUserId", field -> field.label("执行发起人（每次保存替换为修改人）").readOnly())
                                .field("jobPlanId", field -> field.label("作业计划 ID").readOnly())
                                .field("scheduleKind", field -> field.label("计划类型（FIXED_DELAY / CRON）").required())
                                .field("expression", field -> field.label("Cron 表达式"))
                                .field("zoneId", field -> field.label("时区"))
                                .field("intervalMillis", field -> field.label("固定间隔（毫秒）"))
                                .field("initialDelayMillis", field -> field.label("首次延迟（毫秒）"))
                                .field("enabled", field -> field.label("启用状态"))))))
                .build();
    }
}
