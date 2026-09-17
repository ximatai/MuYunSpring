package net.ximatai.muyun.spring.demo.school.teacher;

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

/** 教师的标准 Web 交付入口；教学学科候选项由字段字典声明统一交付，无需专用枚举接口。 */
@RestController
@Profile("school-demo")
@PlatformStaticModule(application = EducationApplication.class, alias = TeacherService.MODULE_ALIAS, title = "教师")
@PlatformMenu(parent = TeachingDemoMenuGroups.ROOT, title = "教师管理", order = 220)
@StaticModuleOpenApi
@RequestMapping("/" + TeacherService.MODULE_ALIAS)
public class TeacherWebController extends WebSupport<TeacherService>
        implements CrudWeb<Teacher, TeacherService>, StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(TeacherService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("教师列表").titleField("title")
                                .secondaryField("teacherNo"))
                        .detail(detail -> detail.editor(form -> form
                                .title("教师档案")
                                .field("teacherNo", field -> field.label("教师编号").required())
                                .field("title", field -> field.label("教师姓名").required())
                                .field("subjectCategoryId", field -> field.label("任教学科").required().recordPicker())
                                .field("studentAssistantId", field -> field.label("学生助理").recordPicker())
                                .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud()
                                .enabledLifecycle()))))
                .build();
    }
}
