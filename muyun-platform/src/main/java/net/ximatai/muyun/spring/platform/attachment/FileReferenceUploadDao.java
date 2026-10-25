package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;

@MuYunRepository
public interface FileReferenceUploadDao extends BaseDao<FileReferenceUpload, String> {}
