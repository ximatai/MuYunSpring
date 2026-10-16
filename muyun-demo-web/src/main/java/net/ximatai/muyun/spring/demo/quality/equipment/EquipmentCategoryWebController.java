package net.ximatai.muyun.spring.demo.quality.equipment;

import net.ximatai.muyun.spring.demo.quality.configuration.*;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("collaboration-demo")
@PlatformStaticModule(application = QualityApplication.class, alias = EquipmentCategoryService.MODULE_ALIAS, title = "设备分类")
@PlatformMenu(parent = QualityDemoMenuDeclarations.ROOT, title = "设备分类", order = 110)
@StaticModuleOpenApi
@RequestMapping("/" + EquipmentCategoryService.MODULE_ALIAS)
public class EquipmentCategoryWebController extends StaticModuleWebControllerAdapter<EquipmentCategoryService>
        implements CrudWeb<EquipmentCategory, EquipmentCategoryService>, StaticModuleUiContributor {
    @Override public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(EquipmentCategoryService.MODULE_ALIAS)
                .page(PageTemplates.treeManagement(page -> page
                        .detail(detail -> detail.emptyDescription("选择设备分类，或新建分类")
                                .editor(form -> form.title("设备分类")
                                        .field("parentId", field -> field.label("上级分类").recordPicker().treeRootTitle("根分类"))
                                        .field("code", field -> field.label("分类编码").required())
                                        .field("title", field -> field.label("分类名称").required())
                                        .field("remark", field -> field.label("备注").uiType("textarea"))
                                        .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }
}
