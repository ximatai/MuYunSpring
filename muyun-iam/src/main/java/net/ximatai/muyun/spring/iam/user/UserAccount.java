package net.ximatai.muyun.spring.iam.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardDataScopedEnabledSortableEntity;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.option.OptionSourceType;

import java.time.Instant;

@Getter
@Setter
@Table(name = "iam_user", comment = "User account")
@TenantUniqueConstraint(fields = "username")
@InitialDataFields(
        identity = {"tenantId", "username"},
        managed = {"authUserId", "authModuleAlias"},
        operator = {"enabled"}
)
public class UserAccount extends StandardDataScopedEnabledSortableEntity {
    @Column(name = "username", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Username")
    private String username;

    @Column(name = "password_hash", type = ColumnType.VARCHAR, length = 256, nullable = false,
            comment = "Password hash")
    @JsonIgnore
    private String passwordHash;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "password_status", type = ColumnType.VARCHAR, length = 32, comment = "Password status")
    private PasswordStatus passwordStatus;

    @OptionLoad(source = "passwordStatus")
    private transient String passwordStatusTitle;

    @Column(name = "password_changed_at", type = ColumnType.TIMESTAMP, comment = "Password changed at")
    private Instant passwordChangedAt;

    @Column(name = "password_expires_at", type = ColumnType.TIMESTAMP, comment = "Password expires at")
    private Instant passwordExpiresAt;

    @Column(name = "last_login_at", type = ColumnType.TIMESTAMP, comment = "Last successful login at")
    private Instant lastLoginAt;

    @Column(name = "last_login_ip", type = ColumnType.VARCHAR, length = 64, comment = "Last successful login IP")
    private String lastLoginIp;

    @Column(name = "last_login_user_agent", type = ColumnType.VARCHAR, length = 512,
            comment = "Last successful login user agent")
    private String lastLoginUserAgent;

    @Column(name = "last_failed_login_at", type = ColumnType.TIMESTAMP, comment = "Last failed login at")
    private Instant lastFailedLoginAt;

    @Column(name = "failed_login_count", type = ColumnType.INT, comment = "Consecutive failed login count")
    private Integer failedLoginCount;

    @Column(name = "locked_until", type = ColumnType.TIMESTAMP, comment = "Account locked until")
    private Instant lockedUntil;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private transient String password;
}
