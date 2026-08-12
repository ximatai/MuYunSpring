package net.ximatai.muyun.spring.platform.ui;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/**
 * Opaque current-user experience preference. Its JSON value is owned by the client.
 * The primary key is the stable identity of tenant, user, client type and preference key.
 */
@Getter
@Setter
@Table(name = "platform_user_preference", comment = "Platform user experience preference")
public class UserPreference extends StandardEntity {
    @Column(name = "user_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "User id")
    private String userId;

    @Column(name = "client_type", type = ColumnType.VARCHAR, length = 16, nullable = false, comment = "Client type")
    private String clientType;

    @Column(name = "preference_key", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Client-owned preference key")
    private String preferenceKey;

    @Column(name = "value_json", type = ColumnType.TEXT, nullable = false, comment = "Client-owned JSON value")
    private String valueJson;
}
