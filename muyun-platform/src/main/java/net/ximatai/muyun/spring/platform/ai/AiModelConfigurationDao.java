package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface AiModelConfigurationDao extends BaseDao<AiModelConfiguration, String> {
}
