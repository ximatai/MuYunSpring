package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface DynamicRuntimeActivationDao extends BaseDao<DynamicRuntimeActivation, String> {}
