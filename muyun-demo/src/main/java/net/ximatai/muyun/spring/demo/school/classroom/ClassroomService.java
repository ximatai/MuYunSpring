package net.ximatai.muyun.spring.demo.school.classroom;

import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;


/** 班级聚合根：成员由主子表能力统一保存，引用、排序、回收站和缓存复用平台能力。 */
@Service
@Profile("school-demo")
public class ClassroomService extends StandardBusinessService<Classroom> implements
        RecycleBinAbility<Classroom>,
        SortAbility<Classroom>,
        ChildrenAbility<Classroom>,
        ReferenceAbility<Classroom>,
        CacheAbility<Classroom> {
    public static final String MODULE_ALIAS = "education.classroom";
    public ClassroomService(ClassroomDao dao) {
        super(MODULE_ALIAS, Classroom.class, dao);
    }
}
