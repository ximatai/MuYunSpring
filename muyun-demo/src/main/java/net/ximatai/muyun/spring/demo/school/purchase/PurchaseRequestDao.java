package net.ximatai.muyun.spring.demo.school.purchase;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
@MuYunRepository
public interface PurchaseRequestDao extends BaseDao<PurchaseRequest, String> {}
