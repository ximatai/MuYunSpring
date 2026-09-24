package net.ximatai.muyun.spring.starter.configuration.platform.fixture;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface MutationContractDao extends BaseDao<MutationContractRecord, String> {}
