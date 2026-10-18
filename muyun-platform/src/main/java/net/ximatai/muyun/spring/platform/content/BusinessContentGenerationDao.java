package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface BusinessContentGenerationDao extends BaseDao<BusinessContentGeneration, String> { }
