package net.ximatai.muyun.spring.demo.school.student;

import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 学生主数据：引用关系及读字段由模型声明，启停、回收站和缓存由能力组合提供。 */
@Service
@Profile("school-demo")
public class StudentService extends StandardBusinessService<Student> implements
        DataScopeAbility<Student>,
        RecycleBinAbility<Student>,
        EnableAbility<Student>,
        CacheAbility<Student>,
        ReferenceAbility<Student> {
    public static final String MODULE_ALIAS = "education.student";

    public StudentService(StudentDao dao) {
        super(MODULE_ALIAS, Student.class, dao);
    }

    /** 在当前动作的数据范围内停用已毕业学生；每轮有界，下一轮继续处理剩余档案。 */
    @Transactional
    public int disableGraduated(LocalDate today) {
        var context = ActionExecutionContextHolder.current().orElseThrow();
        var scope = readScopeByPolicy(context.actionPolicy(), Criteria.of()
                .eq("enabled", true).lte("graduationDate", today));
        return withDataScopeTenant(scope, () -> {
            var candidates = list(scope.criteria(), PageRequest.of(1, 100));
            int changed = 0;
            for (var student : candidates) {
                changed += disable(student.getId(), student.getVersion());
            }
            return changed;
        });
    }
}
