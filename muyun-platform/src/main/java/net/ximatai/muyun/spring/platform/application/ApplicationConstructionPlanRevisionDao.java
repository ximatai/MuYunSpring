package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface ApplicationConstructionPlanRevisionDao extends BaseDao<ApplicationConstructionPlanRevision, String> {}
