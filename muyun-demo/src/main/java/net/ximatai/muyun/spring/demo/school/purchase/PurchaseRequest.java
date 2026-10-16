package net.ximatai.muyun.spring.demo.school.purchase;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.standard.StandardApprovalEntity;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;
import java.math.BigDecimal;

/** Static approval example: approval completes before the purchasing task is fulfilled. */
@Getter @Setter
@Table(name = "education_purchase_request", comment = "教学采购申请")
@Required(fields = "title")
public class PurchaseRequest extends StandardApprovalEntity implements TitledCapable {
    @Column(name = "title", type = ColumnType.VARCHAR, length = 128, nullable = false)
    private String title;
    @Column(name = "amount", type = ColumnType.NUMERIC, precision = 18, scale = 2, comment = "采购金额")
    private BigDecimal amount;
    @Column(name = "delivered", type = ColumnType.BOOLEAN, comment = "已到货")
    private Boolean delivered = Boolean.FALSE;
    @Column(name = "remark", type = ColumnType.TEXT, comment = "备注")
    private String remark;
}
