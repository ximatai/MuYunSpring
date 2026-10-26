package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.iam.application.IamApplication;
import net.ximatai.muyun.spring.iam.federation.*;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnExternalIdentityFederation
@PlatformStaticModule(application = IamApplication.class, alias = JoinApplicationService.MODULE_ALIAS, title = "加入申请")
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, title = "加入申请", order = 72)
@RequestMapping("/iam.join_application")
public class JoinApplicationWebController extends StaticModuleWebControllerAdapter<JoinApplicationService>
        implements StaticQueryViewWeb<JoinApplication, JoinApplicationService>, StaticModuleUiContributor {
    @PostMapping("/{id}/approve")
    @CustomActionEndpoint(value = "approve", title = "批准加入", level = PlatformActionLevel.RECORD, pageInvocable = true)
    public void approve(@PathVariable String id) { webScope(() -> { service().review(id, true, null); return null; }); }

    @PostMapping("/{id}/reject")
    @CustomActionEndpoint(value = "reject", title = "拒绝加入", level = PlatformActionLevel.RECORD, pageInvocable = true)
    public void reject(@PathVariable String id) { webScope(() -> { service().review(id, false, null); return null; }); }

    @Override public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(JoinApplicationService.MODULE_ALIAS)
                .pageAction("approve", PageActionAnchor.DETAIL).pageAction("reject", PageActionAnchor.DETAIL)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields
                                .field("displayName", f -> f.label("申请人"))
                                .field("providerId", f -> f.label("身份源"))
                                .field("status", f -> f.label("申请状态"))
                                .field("createdAt", f -> f.label("申请时间"))))
                        .detail(detail -> detail.display(form -> form.title("加入申请")
                                .field("displayName", f -> f.label("申请人"))
                                .field("externalUserId", f -> f.label("外部用户标识"))
                                .field("status", f -> f.label("申请状态"))
                                .field("reviewerId", f -> f.label("审核人"))
                                .field("reviewedAt", f -> f.label("审核时间")))))).build();
    }
}
