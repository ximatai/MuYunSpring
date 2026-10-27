package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.application.IamApplication;
import net.ximatai.muyun.spring.iam.federation.*;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.*;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExternalIdentityFederation
@PlatformStaticModule(
        application = IamApplication.class,
        alias = DirectorySyncRunService.MODULE_ALIAS,
        title = "通讯录同步运行")
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, title = "通讯录同步运行", order = 71)
@RequestMapping("/iam.directory_sync_run")
public class DirectorySyncRunWebController
        extends StaticModuleWebControllerAdapter<DirectorySyncRunService>
        implements StaticQueryViewWeb<DirectorySyncRun, DirectorySyncRunService>,
                StaticModuleUiContributor {
    @Override public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(DirectorySyncRunService.MODULE_ALIAS)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields
                                .field("mode", field -> field.label("执行模式"))
                                .field("status", field -> field.label("运行状态"))
                                .field("completed", field -> field.label("整轮完成"))
                                .field("createdAt", field -> field.label("创建时间")))
                                .persistentQueries(queries -> {
                                    queries.field("mode", "mode", net.ximatai.muyun.spring.ability.query.QueryOperator.EQ,
                                            control -> control.label("执行模式"));
                                    queries.field("status", "status", net.ximatai.muyun.spring.ability.query.QueryOperator.EQ,
                                            control -> control.label("运行状态"));
                                }))
                        .detail(detail -> detail.display(form -> form.title("通讯录同步运行")
                                .group("result", "执行结果", "预览不写入人员数据；整轮完成表示目录已遍历完毕，具体结果请结合运行状态查看。", group -> group
                                        .field("mode", field -> field.label("执行模式"))
                                        .field("status", field -> field.label("运行状态"))
                                        .field("completed", field -> field.label("目录遍历完成").booleanStatus("已完成", "未完成").readOnly())
                                        .field("createdAt", field -> field.label("运行创建时间")))
                                .group("diagnostics", "统计与诊断", "失败页用于定位来源读取异常；续跑游标由系统维护。", group -> group
                                        .field("statistics", field -> field.label("统计摘要").columnSpan(2))
                                        .field("failedPages", field -> field.label("失败页详情").columnSpan(2))
                                        .field("cursor", field -> field.label("续跑游标").columnSpan(2)))
                                .group("trace", "执行关联", null, group -> group
                                        .field("policyId", field -> field.label("租户接入策略标识"))
                                        .field("scopeId", field -> field.label("范围版本"))
                                        .field("jobRunId", field -> field.label("关联作业运行标识").columnSpan(2)))))))
                .build();
    }
}
