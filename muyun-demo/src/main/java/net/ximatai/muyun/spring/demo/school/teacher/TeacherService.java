package net.ximatai.muyun.spring.demo.school.teacher;

import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 教师主数据：作为班主任引用目标，并通过模型声明学科、助理及关联读字段。 */
@Service
@Profile("school-demo")
public class TeacherService extends StandardBusinessService<Teacher> implements
        SoftDeleteAbility<Teacher>,
        EnableAbility<Teacher>,
        CacheAbility<Teacher>,
        ReferenceAbility<Teacher> {
    public static final String MODULE_ALIAS = "education.teacher";

    public TeacherService(TeacherDao dao) {
        super(MODULE_ALIAS, Teacher.class, dao);
    }
}
