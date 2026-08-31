package net.ximatai.muyun.spring.demo.school.teacher;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferencerAbility;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 教师主数据的标准 Service：{@link ReferenceAbility} 让班级选择班主任，
 * 并作为“班级 → 班主任 → 学生助理”多 hop 投影的中间引用目标。{@link ReferencerAbility}
 * 让教师对学科分类的引用也进入统一的写入完整性与依赖失效链路。
 * 启停、软删和缓存使用平台默认能力，避免为主数据重复编排生命周期。
 */
@Service
@Profile("school-demo")
public class TeacherService extends AbstractAbilityService<Teacher> implements
        SoftDeleteAbility<Teacher>,
        EnableAbility<Teacher>,
        CacheAbility<Teacher>,
        ReferenceAbility<Teacher>,
        ReferencerAbility<Teacher> {
    public static final String MODULE_ALIAS = "education.teacher";

    public TeacherService(TeacherDao dao) {
        super(MODULE_ALIAS, Teacher.class, dao);
    }
}
