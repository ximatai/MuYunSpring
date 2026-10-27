package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface PersonalAccessTokenDao extends BaseDao<PersonalAccessToken, String> {}
