package net.ximatai.muyun.spring.demo.school.classroom;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.ReferencerAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;


/**
 * 班级聚合根的标准 Service：{@link ChildrenAbility} 将 {@code members} 纳入同一保存与删除链路；
 * {@link ReferenceAbility} 让班级成为可选引用目标，排序、回收站和缓存则复用平台默认能力。
 */
@Service
@Profile("school-demo")
public class ClassroomService extends AbstractAbilityService<Classroom> implements
        RecycleBinAbility<Classroom>,
        SortAbility<Classroom>,
        ChildrenAbility<Classroom>,
        ReferencerAbility<Classroom>,
        ReferenceAbility<Classroom>,
        CacheAbility<Classroom> {
    public static final String MODULE_ALIAS = "education.classroom";
    public ClassroomService(ClassroomDao dao) {
        super(MODULE_ALIAS, Classroom.class, dao);
    }
}
