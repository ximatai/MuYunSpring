package net.ximatai.muyun.spring.demo.school.classroom;

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
 * 班级的标准 Web 交付入口。
 * 成员随班级请求中的 {@code members} 保存，故不暴露脱离聚合生命周期的成员 Controller；
 * 排序与回收站端点由 Service Ability 自动投射。
 */
@RestController
@Profile("school-demo")
@PlatformStaticModule(application = EducationApplication.class, alias = ClassroomService.MODULE_ALIAS, title = "班级")
@PlatformMenu(parent = TeachingDemoMenuGroups.ROOT, title = "班级管理", order = 210)
@StaticModuleOpenApi
@RequestMapping("/" + ClassroomService.MODULE_ALIAS)
public class ClassroomWebController extends WebSupport<ClassroomService>
        implements CrudWeb<Classroom, ClassroomService>, StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(ClassroomService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("班级列表").titleField("title")
                                .secondaryField("classCode"))
                        .detail(detail -> detail.editor(form -> form
                                .title("班级档案")
                                .field("classCode", field -> field.label("班级编号").required())
                                .field("title", field -> field.label("班级名称").required())
                                .field("academicYear", field -> field.label("学年").required())
                                .field("homeroomTeacherId", field -> field.label("班主任").required().recordPicker())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().recycleBin()))))
                .editorContribution("members", form -> form.title("班级成员")
                        .field("members", "studentId", field -> field.label("学生").required().recordPicker())
                        .field("members", "sortOrder", field -> field.label("排序号")))
                .relation("members", relation -> relation.aggregateChild(child -> child
                        .title("班级成员")
                        .targetEntity("members")
                        .parentBinding("classroomId")))
                .build();
    }
}
