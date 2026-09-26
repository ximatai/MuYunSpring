package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.BaseDao;

@MuYunRepository
public interface AssistantConversationDao extends BaseDao<AssistantConversation, String> {}
