package net.ximatai.muyun.spring.demo.school.student;

import net.ximatai.muyun.spring.platform.module.ScheduledModuleAction;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/** 无记录、无表单的定时动作；授权由模块动作门面负责，业务变更由学生服务负责。 */
@Component
@Profile("school-demo")
public class DisableGraduatedStudentsAction implements ScheduledModuleAction {
    public static final String ACTION_CODE = "disableGraduated";
    private final StudentService students;

    public DisableGraduatedStudentsAction(StudentService students) {
        this.students = students;
    }

    @Override public String moduleAlias() { return StudentService.MODULE_ALIAS; }
    @Override public String actionCode() { return ACTION_CODE; }

    @Override
    public void validate(Map<String, Object> parameters) {
        if (!parameters.isEmpty()) throw PlatformErrors.badRequestText(TextReference.code(
                "demo.student.graduation.no-parameters", "停用已毕业学生不接受参数"));
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        return Map.of("disabledStudents", students.disableGraduated(LocalDate.now(ZoneOffset.UTC)));
    }
}
