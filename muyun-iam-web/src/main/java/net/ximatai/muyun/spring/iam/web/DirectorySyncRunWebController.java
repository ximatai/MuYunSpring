package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.federation.*;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExternalIdentityFederation
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = DirectorySyncRunService.MODULE_ALIAS, title = "通讯录同步运行")
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, title = "通讯录同步运行", order = 71)
@RequestMapping("/iam.directory_sync_run")
public class DirectorySyncRunWebController extends StaticModuleWebControllerAdapter<DirectorySyncRunService>
        implements StaticQueryViewWeb<DirectorySyncRun, DirectorySyncRunService>, StaticModuleUiContributor {
    @Override public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(DirectorySyncRunService.MODULE_ALIAS)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields
                                .field("mode", field -> field.label("执行模式"))
                                .field("status", field -> field.label("运行状态"))
                                .field("completed", field -> field.label("整轮完成"))
                                .field("createdAt", field -> field.label("创建时间"))))
                        .detail(detail -> detail.display(form -> form.title("通讯录同步运行")
                                .field("policyId", field -> field.label("租户接入策略"))
                                .field("scopeId", field -> field.label("范围版本"))
                                .field("jobRunId", field -> field.label("关联作业运行"))
                                .field("mode", field -> field.label("执行模式"))
                                .field("status", field -> field.label("运行状态"))
                                .field("completed", field -> field.label("整轮完成"))
                                .field("statistics", field -> field.label("统计摘要"))
                                .field("failedPages", field -> field.label("失败页"))
                                .field("cursor", field -> field.label("续跑游标"))))))
                .build();
    }
}
