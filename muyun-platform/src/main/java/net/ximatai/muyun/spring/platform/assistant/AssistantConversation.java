package net.ximatai.muyun.spring.platform.assistant;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_assistant_conversation", comment = "Personal assistant conversation history")
public class AssistantConversation extends StandardEntity {
    @Indexed
    @Column(name = "owner_id", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String ownerId;
    @Column(name = "scope_key", type = ColumnType.VARCHAR, length = 1024, nullable = false)
    private String scopeKey;
    @Column(name = "title", type = ColumnType.VARCHAR, length = 120, nullable = false)
    private String title;
    @Column(name = "content_json", type = ColumnType.TEXT, nullable = false)
    private String contentJson;
}
