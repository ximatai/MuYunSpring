package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface ModuleChildMetadataCreationReceiptDao extends BaseDao<ModuleChildMetadataCreationReceipt, String> {}
