package net.ximatai.muyun.spring.platform.code.fixture;
import lombok.*;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.code.BusinessCode;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.constraint.Required;
@Getter @Setter
@Table(name = "code_static_line_contract", comment = "Static aggregate business coding contract")
public class StaticCodeLine extends StandardEntity {
    @Column(name = "parent_id", type = ColumnType.VARCHAR, length = 32, nullable = false) private String parentId;
    @BusinessCode @Column(name = "line_no", type = ColumnType.VARCHAR, length = 64) private String lineNo;
    @Required @Column(name = "subject", type = ColumnType.VARCHAR, length = 64) private String subject;
}
