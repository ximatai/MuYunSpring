package net.ximatai.muyun.spring.demo.school.receipt;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.code.*;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** API-only static business: persisted numbering plus a separately authorized confirmation number. */
@Getter @Setter
@Table(name = "education_receipt", comment = "业务回执")
@Required(fields = "subject")
public class Receipt extends StandardEntity {
    @BusinessCode(primary = true)
    @Column(name = "document_no", type = ColumnType.VARCHAR, length = 64, comment = "回执单号")
    private String documentNo;
    @BusinessCode(trigger = CodeTrigger.EXPLICIT)
    @Column(name = "confirmation_no", type = ColumnType.VARCHAR, length = 64, comment = "确认编号")
    private String confirmationNo;
    @Column(name = "subject", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "回执主题")
    private String subject;
}
