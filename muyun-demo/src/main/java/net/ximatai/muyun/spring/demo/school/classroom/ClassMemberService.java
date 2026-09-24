package net.ximatai.muyun.spring.demo.school.classroom;

import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 班级成员子表：班内排序、历史保留与引用完整性由平台能力和模型声明提供。 */
@Service
@Profile("school-demo")
public class ClassMemberService extends StandardBusinessService<ClassMember> implements
        SoftDeleteAbility<ClassMember>,
        SortAbility<ClassMember>,
        ChildAbility<ClassMember> {

    public ClassMemberService(ClassMemberDao dao) {
        super("education.class_member", ClassMember.class, dao);
    }
}
