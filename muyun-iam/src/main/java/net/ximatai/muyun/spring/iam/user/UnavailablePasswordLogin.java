package net.ximatai.muyun.spring.iam.user;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import java.time.Instant;

/** Internal failure state for a claimed login name; its ID hashes the tenant and name. No user is created. */
@Getter
@Setter
@Table(name = "iam_unavailable_password_login", comment = "Unavailable password login failure state")
@CompositeIndex(columns = {"retain_until"})
public class UnavailablePasswordLogin extends StandardEntity {
    @Column(name = "failed_attempts", type = ColumnType.INT, nullable = false)
    private Integer failedAttempts;
    @Column(name = "locked_until", type = ColumnType.TIMESTAMP)
    private Instant lockedUntil;
    @Column(name = "retain_until", type = ColumnType.TIMESTAMP, nullable = false)
    private Instant retainUntil;
}
