package net.ximatai.muyun.spring.iam.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.option.OptionSourceType;

/**
 * Password security policy for one ownership scope: the platform default ({@code tenantId == null})
 * or a single tenant override ({@code tenantId == <tenant>}). Each scope owns exactly one record.
 *
 * <p>Password complexity and login failure lockout are two independent inheritance groups. A tenant
 * record either inherits the platform value for a group ({@link PasswordSecurityPolicyMode#INHERIT})
 * or replaces the whole group ({@link PasswordSecurityPolicyMode#CUSTOM}); the groups are never merged
 * field by field. The actual complexity regex rules live in {@link PasswordPolicyRule}, owned by the
 * same tenant scope. The platform record is always locally configured.</p>
 *
 * <p>Ownership is fixed at creation and cannot be migrated. The {@code id} is a deterministic scope
 * identity derived on the server ({@code <= 32} chars): the platform default uses a fixed constant and
 * a tenant uses {@code iam.psp.} plus a 24-hex SHA-256 of its scope. The primary key (deterministic id)
 * and the unique {@code ownership_scope_key} index are the database backstops so two concurrent
 * creations for the same scope cannot both commit, including the null-tenant platform default.</p>
 */
@Getter
@Setter
@Table(name = "iam_password_security_policy", comment = "Password security policy: platform default and per-tenant override")
@CompositeIndex(columns = {"ownership_scope_key"}, unique = true)
public class PasswordSecurityPolicy extends StandardTitledEntity {
    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "complexity_mode", type = ColumnType.VARCHAR, length = 16, nullable = false,
            comment = "Password complexity inheritance mode", defaultVal = @Default(varchar = "inherit"))
    private PasswordSecurityPolicyMode complexityMode = PasswordSecurityPolicyMode.INHERIT;

    @OptionLoad(source = "complexityMode")
    private transient String complexityModeTitle;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "lockout_mode", type = ColumnType.VARCHAR, length = 16, nullable = false,
            comment = "Login failure lockout inheritance mode", defaultVal = @Default(varchar = "inherit"))
    private PasswordSecurityPolicyMode lockoutMode = PasswordSecurityPolicyMode.INHERIT;

    @OptionLoad(source = "lockoutMode")
    private transient String lockoutModeTitle;

    @Column(name = "max_failed_attempts", type = ColumnType.INT,
            comment = "Consecutive login failures that lock the account; 0 disables lockout")
    private Integer maxFailedAttempts;

    @Column(name = "lock_duration_seconds", type = ColumnType.INT,
            comment = "Lock duration in seconds; positive while lockout is enabled")
    private Integer lockDurationSeconds;

    /** Occupied for every record; server-derived from the ownership scope and cannot be forged. */
    @JsonIgnore
    @Column(name = "ownership_scope_key", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Stable ownership scope key: P for platform, T:<tenant> for a tenant")
    private String ownershipScopeKey;
}
