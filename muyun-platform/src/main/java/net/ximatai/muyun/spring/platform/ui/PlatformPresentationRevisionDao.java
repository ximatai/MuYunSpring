package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface PlatformPresentationRevisionDao extends BaseDao<PlatformPresentationRevision, String> {
}
