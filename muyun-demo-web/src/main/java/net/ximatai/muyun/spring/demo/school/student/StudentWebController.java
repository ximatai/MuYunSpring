package net.ximatai.muyun.spring.demo.school.student;

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

/**
 * 学生的标准 Web 交付入口；{@link CrudWeb} 提供 CRUD 与表单/查询 schema。
 * 启停和回收站由 Service Ability 自动投射，不在 Controller 重复实现。
 */
@RestController
@Profile("school-demo")
@PlatformStaticModule(application = EducationApplication.class, alias = StudentService.MODULE_ALIAS, title = "学生")
@PlatformMenu(parent = TeachingDemoMenuGroups.ROOT, title = "学生管理", order = 200)
@StaticModuleOpenApi
@RequestMapping("/" + StudentService.MODULE_ALIAS)
public class StudentWebController extends WebSupport<StudentService>
        implements CrudWeb<Student, StudentService>, StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(StudentService.MODULE_ALIAS)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields
                                .title("学生列表")
                                .field("studentNo", field -> field.label("学号").width("140px"))
                                .field("title", field -> field.label("学生姓名").width("160px"))
                                .field("grade", field -> field.label("年级").width("120px"))
                                .field("enabled", field -> field.label("启用状态").enabledStatus()
                                        .width("90px").align("center"))))
                        .detail(detail -> detail.editor(form -> form
                                .title("学生档案")
                                .field("studentNo", field -> field.label("学号").required())
                                .field("title", field -> field.label("学生姓名").required())
                                .field("grade", field -> field.label("年级").required())
                                .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud()
                                .enabledLifecycle().recycleBin()))))
                .build();
    }
}
