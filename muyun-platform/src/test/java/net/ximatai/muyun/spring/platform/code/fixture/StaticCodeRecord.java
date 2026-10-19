package net.ximatai.muyun.spring.platform.code.fixture;

import lombok.*;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.code.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.constraint.Required;

@Getter @Setter
@Table(name = "code_static_contract", comment = "Static business coding contract")
public class StaticCodeRecord extends StandardEntity {
    @net.ximatai.muyun.spring.common.model.constraint.NormalizeText
    @BusinessCode(primary = true)
    @Column(name = "document_no", type = ColumnType.VARCHAR, length = 64)
    private String documentNo;
    @BusinessCode(trigger = CodeTrigger.EXPLICIT)
    @Column(name = "confirmation_no", type = ColumnType.VARCHAR, length = 64)
    private String confirmationNo;
    private java.util.List<StaticCodeLine> lines;
    @Column(name = "business_organization_id", type = ColumnType.VARCHAR, length = 64)
    private String businessOrganizationId;
    @Required
    @Column(name = "subject", type = ColumnType.VARCHAR, length = 100)
    private String subject;
}
