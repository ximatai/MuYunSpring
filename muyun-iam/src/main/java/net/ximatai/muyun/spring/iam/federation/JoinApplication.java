package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import java.time.Instant;

/** A tenant's durable admission decision; pending applications never create disabled accounts. */
@Getter
@Setter
@Table(name = "iam_join_application", comment = "External identity join application")
@TenantUniqueConstraint(fields = {"providerId", "externalEnterpriseId", "externalUserId"})
public class JoinApplication extends StandardEntity {
    @Column(name = "provider_id", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String providerId;
    @Column(name = "authority_fingerprint", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String authorityFingerprint;
    @Column(name = "external_enterprise_id", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String externalEnterpriseId;
    @Column(name = "external_user_id", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String externalUserId;
    @Column(name = "display_name", type = ColumnType.VARCHAR, length = 255)
    private String displayName;
    @Column(name = "status", type = ColumnType.VARCHAR, length = 32, nullable = false)
    @net.ximatai.muyun.spring.common.option.OptionField(type = net.ximatai.muyun.spring.common.option.OptionSourceType.ENUM)
    private JoinApplicationStatus status;
    @Column(name = "user_id", type = ColumnType.VARCHAR, length = 32)
    private String userId;
    @Column(name = "reviewer_id", type = ColumnType.VARCHAR, length = 32)
    private String reviewerId;
    @Column(name = "reviewed_at", type = ColumnType.TIMESTAMP)
    private Instant reviewedAt;
    @Column(name = "review_note", type = ColumnType.TEXT)
    private String reviewNote;
}
