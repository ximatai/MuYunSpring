package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.CurrentTenantNavigatorReferenceWeb;
import net.ximatai.muyun.spring.platform.web.StaticModuleWebControllerAdapter;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = "iam.tenant", title = "租户管理")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, order = 10)
@RequestMapping("/iam.tenant")
public class TenantWebController extends StaticModuleWebControllerAdapter<TenantService> implements
        CrudWeb<Tenant, TenantService>,
        CurrentTenantNavigatorReferenceWeb<Tenant, TenantService>,
        SystemScope<TenantService>,
        StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(TenantService.MODULE_ALIAS)
                .typedTextConfirmation("delete", "alias")
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("租户列表").titleField("title").secondaryField("alias"))
                        .detail(detail -> detail.editor(form -> form
                        .title("租户档案")
                        .field("alias", field -> field.label("租户 alias"))
                        .field("title", field -> field.label("租户名称"))
                        .group("workbench_branding", "工作台品牌", "配置 Logo、主标题与副标题的展示方式。", branding -> branding
                                .field("workbenchBrandMode", field -> field.label("展示方式").columnSpan(2))
                                .field("workbenchTitle", field -> field.label("主标题"))
                                .field("workbenchSubtitle", field -> field.label("副标题（可选）"))
                                .field("lightLogoAssetId", field -> field.label("展示 Logo（默认）"))
                                .field("darkLogoAssetId", field -> field.label("展示 Logo（暗色模式）")))))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().recycleBin()))))
                .build();
    }

}
