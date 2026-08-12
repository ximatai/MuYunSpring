package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface ManagedFileAssetReferenceDao extends BaseDao<ManagedFileAssetReference, String> {
}
