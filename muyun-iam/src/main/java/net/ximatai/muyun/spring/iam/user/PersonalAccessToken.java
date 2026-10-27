package net.ximatai.muyun.spring.iam.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import java.time.Instant;

/** A user's tool credential. Inherited tenantId is always null, never a target-tenant binding. */
@Getter
@Setter
@Table(name = "iam_personal_access_token", comment = "Personal tool access credential")
@CompositeIndex(columns = {"token_hash"}, unique = true)
@CompositeIndex(columns = {"user_id", "created_at"})
public class PersonalAccessToken extends StandardEntity {
    @Column(name = "user_id", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String userId;
    @Column(name = "name", type = ColumnType.VARCHAR, length = 100, nullable = false)
    private String name;
    @JsonIgnore
    @Column(name = "token_hash", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String tokenHash;
    @Column(name = "display_prefix", type = ColumnType.VARCHAR, length = 24, nullable = false)
    private String displayPrefix;
    @Column(name = "expires_at", type = ColumnType.TIMESTAMP, nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at", type = ColumnType.TIMESTAMP)
    private Instant revokedAt;
    @Column(name = "revoked_reason", type = ColumnType.VARCHAR, length = 128)
    private String revokedReason;
    @Column(name = "last_used_at", type = ColumnType.TIMESTAMP)
    private Instant lastUsedAt;
}
