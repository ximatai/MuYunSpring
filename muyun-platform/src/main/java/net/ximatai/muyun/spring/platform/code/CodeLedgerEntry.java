package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_code_ledger_entry", comment = "Platform code ledger entry")
@CompositeIndex(columns = {"tenant_id", "rule_id", "code_value"}, unique = true)
public class CodeLedgerEntry extends StandardEntity {
    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Code rule id")
    @Required
    private String ruleId;

    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Module alias")
    @Required
    private String moduleAlias;

    @Column(name = "entity_alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Entity alias")
    @Required
    private String entityAlias;

    @Column(name = "field_name", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Code field name")
    @Required
    private String fieldName;

    @Indexed
    @Column(name = "code_value", type = ColumnType.VARCHAR, length = 256, nullable = false, comment = "Code value")
    @Required
    private String codeValue;

    @Indexed
    @Column(name = "basis_key", type = ColumnType.VARCHAR, length = 512, nullable = false, comment = "Sequence basis key",
            defaultVal = @Default(varchar = CodeSequenceState.DEFAULT_BUCKET))
    private String basisKey = CodeSequenceState.DEFAULT_BUCKET;

    @Indexed
    @Column(name = "period_key", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Sequence period key",
            defaultVal = @Default(varchar = CodeSequenceState.DEFAULT_BUCKET))
    private String periodKey = CodeSequenceState.DEFAULT_BUCKET;

    @Indexed
    @Column(name = "source_record_id", type = ColumnType.VARCHAR, length = 32, comment = "Current source record id")
    private String sourceRecordId;

    @Indexed
    @Column(name = "status", type = ColumnType.VARCHAR, length = 16, nullable = false, comment = "Ledger status",
            defaultVal = @Default(varchar = "ACTIVE"))
    private CodeLedgerStatus status = CodeLedgerStatus.ACTIVE;

    @Column(name = "last_action", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Last lifecycle action",
            defaultVal = @Default(varchar = "ASSIGNED"))
    private CodeLedgerAction lastAction = CodeLedgerAction.ASSIGNED;
}
