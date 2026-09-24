package net.ximatai.muyun.spring.starter.configuration.platform.fixture;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;

@Getter @Setter
@Table(name = "test_mutation_contract")
@TenantUniqueConstraint(fields = "code", message = "code already exists")
public class MutationContractRecord extends StandardEntity implements TreeCapable {
    @Column(type = ColumnType.VARCHAR, length = 100)
    private String parentId;
    @Column(type = ColumnType.INT)
    private Integer sortOrder;
    @Column(type = ColumnType.VARCHAR, length = 100)
    private String code;
}
