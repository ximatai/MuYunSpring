package net.ximatai.muyun.spring.demo.school.subject;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface SubjectCategoryDao extends BaseDao<SubjectCategory, String> {
}
