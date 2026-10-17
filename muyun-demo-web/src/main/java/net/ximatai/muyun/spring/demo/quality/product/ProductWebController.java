package net.ximatai.muyun.spring.demo.quality.product;

import net.ximatai.muyun.spring.demo.quality.configuration.*;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.*;
import org.springframework.context.annotation.Profile;
import net.ximatai.muyun.spring.web.NavigatorReferenceWeb;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("collaboration-demo")
@PlatformStaticModule(application = QualityApplication.class, alias = ProductService.MODULE_ALIAS, title = "商品目录",
        capabilities = net.ximatai.muyun.spring.common.platform.EntityCapability.EXCHANGE)
@PlatformMenu(parent = QualityDemoMenuDeclarations.ROOT, title = "商品目录", order = 120)
@StaticModuleOpenApi
@RequestMapping("/" + ProductService.MODULE_ALIAS)
public class ProductWebController extends StaticModuleWebControllerAdapter<ProductService>
        implements CrudWeb<Product, ProductService>, NavigatorReferenceWeb<Product, ProductService>,
        StaticModuleUiContributor {
    @Override public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(ProductService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("商品目录").recordLabel("商品").titleField("title").secondaryField("code"))
                        .detail(detail -> detail.createTitle("新增商品").editor(form -> form.title("商品资料")
                                .field("code", field -> field.label("商品编码").required())
                                .field("title", field -> field.label("商品名称").required())
                                .field("categoryId", field -> field.label("设备分类").required().recordPicker())
                                .field("description", field -> field.label("商品说明").uiType("textarea"))
                                .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }
}
