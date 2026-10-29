package net.ximatai.muyun.spring.platform.code;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Commit evidence only; never stores or replays authored business values. */
@Getter
@Setter
@Table(name = "platform_code_rule_save_receipt", comment = "Code rule save receipt")
public class CodeRuleSaveReceipt extends StandardEntity {
    @Column(type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String requestDigest;
    @Column(type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String payloadDigest;
    @Column(type = ColumnType.VARCHAR, length = 320, nullable = false)
    private String targetKey;
    @Column(type = ColumnType.VARCHAR, length = 64)
    private String ruleId;
    @Column(type = ColumnType.INT)
    private Integer savedVersion;
    @Column(type = ColumnType.BOOLEAN, nullable = false)
    private Boolean cancelled;
}
