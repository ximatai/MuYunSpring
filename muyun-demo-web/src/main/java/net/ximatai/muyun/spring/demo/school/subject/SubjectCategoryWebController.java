package net.ximatai.muyun.spring.demo.school.subject;

import net.ximatai.muyun.spring.demo.school.configuration.EducationApplication;
import net.ximatai.muyun.spring.demo.school.configuration.TeachingDemoMenuGroups;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

/** 学科分类树的标准 Web 交付入口。 */
@RestController
@Profile("school-demo")
@PlatformStaticModule(application = EducationApplication.class, alias = SubjectCategoryService.MODULE_ALIAS, title = "学科分类")
@PlatformMenu(parent = TeachingDemoMenuGroups.ROOT, title = "学科分类", order = 230)
@StaticModuleOpenApi
@RequestMapping("/" + SubjectCategoryService.MODULE_ALIAS)
public class SubjectCategoryWebController extends WebSupport<SubjectCategoryService>
        implements CrudWeb<SubjectCategory, SubjectCategoryService>, StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(SubjectCategoryService.MODULE_ALIAS)
                .page(PageTemplates.treeManagement(page -> page
                        .detail(detail -> detail
                                .emptyDescription("请选择学科分类，或新建根分类")
                                .editor(form -> form
                                        .title("学科分类")
                                        .field("parentId", field -> field.label("上级分类").recordPicker()
                                                .treeRootTitle("根分类"))
                                        .field("code", field -> field.label("分类编码").required())
                                        .field("title", field -> field.label("分类名称").required())
                                        .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud()
                                .enabledLifecycle()))))
                .build();
    }
}
