package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface ConfigurationTranslationDao extends BaseDao<ConfigurationTranslation, String> { }
