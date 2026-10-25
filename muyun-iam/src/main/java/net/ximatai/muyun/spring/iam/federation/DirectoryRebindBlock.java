package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** An explicit unbind must survive later directory imports. Never exposed as an editable module. */
@Getter
@Setter
@Table(name = "iam_directory_rebind_block", comment = "Explicit external identity unbind")
@TenantUniqueConstraint(fields = {"providerId", "externalUserId"})
public class DirectoryRebindBlock extends StandardEntity {
    @Column(name = "provider_id", type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String providerId;
    @Column(name = "external_user_id", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String externalUserId;
}
