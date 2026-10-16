package net.ximatai.muyun.spring.demo.quality.product;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface ProductDao extends BaseDao<Product, String> { }
