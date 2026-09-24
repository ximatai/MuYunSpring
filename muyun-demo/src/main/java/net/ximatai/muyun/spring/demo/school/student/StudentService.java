package net.ximatai.muyun.spring.demo.school.student;

import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 学生主数据：引用关系及读字段由模型声明，启停、回收站和缓存由能力组合提供。 */
@Service
@Profile("school-demo")
public class StudentService extends StandardBusinessService<Student> implements
        RecycleBinAbility<Student>,
        EnableAbility<Student>,
        CacheAbility<Student>,
        ReferenceAbility<Student> {
    public static final String MODULE_ALIAS = "education.student";

    public StudentService(StudentDao dao) {
        super(MODULE_ALIAS, Student.class, dao);
    }

}
