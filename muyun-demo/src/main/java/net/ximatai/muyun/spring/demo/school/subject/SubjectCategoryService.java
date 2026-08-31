package net.ximatai.muyun.spring.demo.school.subject;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 学科分类树的标准服务，作为静态教学组织和动态测评共用的引用目标。 */
@Service
@Profile("school-demo")
public class SubjectCategoryService extends AbstractAbilityService<SubjectCategory> implements
        SoftDeleteAbility<SubjectCategory>,
        EnableAbility<SubjectCategory>,
        TreeAbility<SubjectCategory>,
        CacheAbility<SubjectCategory>,
        ReferenceAbility<SubjectCategory> {
    public static final String MODULE_ALIAS = "education.subject_category";

    public SubjectCategoryService(SubjectCategoryDao dao) {
        super(MODULE_ALIAS, SubjectCategory.class, dao);
    }
}
