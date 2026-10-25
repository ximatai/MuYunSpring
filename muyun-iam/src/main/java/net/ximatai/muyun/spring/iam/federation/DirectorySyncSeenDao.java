package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;
@MuYunRepository
public interface DirectorySyncSeenDao extends BaseDao<DirectorySyncSeen, String> { }
