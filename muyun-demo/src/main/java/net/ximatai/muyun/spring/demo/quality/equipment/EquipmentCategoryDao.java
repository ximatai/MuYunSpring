package net.ximatai.muyun.spring.demo.quality.equipment;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface EquipmentCategoryDao extends BaseDao<EquipmentCategory, String> { }
