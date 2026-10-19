package net.ximatai.muyun.spring.demo.school.receipt;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;

@MuYunRepository
public interface ReceiptDao extends BaseDao<Receipt, String> { }
