package net.ximatai.muyun.spring.iam.user;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;

@Getter
@Setter
@Table(name = "iam_user_session", comment = "User login session")
@CompositeIndex(columns = {"token_hash"}, unique = true)
@CompositeIndex(columns = {"tenant_id", "user_id", "revoked_at"})
@CompositeIndex(columns = {"revoked_at", "id"})
@CompositeIndex(columns = {"expires_at", "id"})
@CompositeIndex(columns = {"max_expires_at", "id"})
public class UserSession extends StandardEntity {
    @Column(name = "user_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "User id")
    private String userId;

    @Column(name = "username", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Username snapshot")
    private String username;

    @Column(name = "organization_id", type = ColumnType.VARCHAR, length = 64, comment = "Organization id snapshot")
    private String organizationId;

    @Column(name = "token_hash", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Token SHA-256 hash")
    private String tokenHash;

    @Column(name = "issued_at", type = ColumnType.TIMESTAMP, nullable = false, comment = "Issued at")
    private Instant issuedAt;

    @Column(name = "expires_at", type = ColumnType.TIMESTAMP, nullable = false, comment = "Expires at")
    private Instant expiresAt;

    @Column(name = "max_expires_at", type = ColumnType.TIMESTAMP, nullable = false,
            comment = "Absolute expiration time")
    private Instant maxExpiresAt;

    @Column(name = "last_seen_at", type = ColumnType.TIMESTAMP, comment = "Last seen at")
    private Instant lastSeenAt;

    @Column(name = "password_change_required", type = ColumnType.BOOLEAN,
            comment = "Whether this session is restricted to password change")
    private Boolean passwordChangeRequired;

    @Column(name = "authentication_method", type = ColumnType.VARCHAR, length = 64,
            comment = "How this session was authenticated")
    private String authenticationMethod;

    @Column(name = "login_ip", type = ColumnType.VARCHAR, length = 64, comment = "Login IP snapshot")
    private String loginIp;

    @Column(name = "login_user_agent", type = ColumnType.VARCHAR, length = 512, comment = "Login user agent snapshot")
    private String loginUserAgent;

    @Column(name = "revoked_at", type = ColumnType.TIMESTAMP, comment = "Revoked at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", type = ColumnType.VARCHAR, length = 128, comment = "Revoked reason")
    private String revokedReason;
}
