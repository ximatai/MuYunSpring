package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
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
        alias = "iam.tenant", title = "租户管理", route = "/iam/tenants")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, order = 10)
@RequestMapping("/iam.tenant")
public class TenantWebController extends WebSupport<TenantService> implements
        CrudWeb<Tenant, TenantService>,
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
                        // These fields expose the governed file-reference contract. The tenant page itself owns
                        // the mode-dependent layout, validation, and user guidance as static business UI.
                        .field("workbenchBrandMode", field -> field.label("工作台品牌展示方式"))
                        .field("workbenchTitle", field -> field.label("主标题"))
                        .field("workbenchSubtitle", field -> field.label("副标题"))
                        .field("lightLogoAssetId", field -> field.label("展示 Logo（默认）"))
                        .field("darkLogoAssetId", field -> field.label("展示 Logo（暗色模式）"))))
                        .traits(traits -> traits.standardCrud().recycleBin())))
                .build();
    }
}
