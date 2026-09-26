package net.ximatai.muyun.spring.platform.save;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface RecordSaveReceiptDao extends BaseDao<RecordSaveReceipt, String> {}
