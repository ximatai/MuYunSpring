package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.capability.EnabledCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;
import java.util.Set;

@Getter
@Setter
@Table(name = "iam_employee_delegation", comment = "Employee delegation")
@CompositeIndex(columns = {"tenant_id", "principal_employee_id", "enabled"})
@CompositeIndex(columns = {"tenant_id", "delegate_employee_id", "enabled"})
public class EmployeeDelegation extends StandardEntity implements EnabledCapable {
    @Column(name = "delegation_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Delegation type", defaultVal = @Default(varchar = "business"))
    private EmployeeDelegationType delegationType = EmployeeDelegationType.BUSINESS;

    @Column(name = "principal_employee_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Principal employee id")
    @Required
    @NormalizeText
    private String principalEmployeeId;

    @Column(name = "principal_position_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Principal employee position id")
    @NormalizeText(TextNormalization.TRIM_TO_NULL)
    private String principalPositionId;

    @Column(name = "delegate_employee_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Delegate employee id")
    @Required
    @NormalizeText
    private String delegateEmployeeId;

    @Column(name = "delegate_position_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Delegate employee position id")
    @NormalizeText(TextNormalization.TRIM_TO_NULL)
    private String delegatePositionId;

    @Column(name = "effective_from", type = ColumnType.TIMESTAMP, comment = "Effective from")
    private Instant effectiveFrom;

    @Column(name = "effective_to", type = ColumnType.TIMESTAMP, comment = "Effective to")
    private Instant effectiveTo;

    @Column(name = "module_scope_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Module scope type", defaultVal = @Default(varchar = "all"))
    private EmployeeDelegationScopeType moduleScopeType = EmployeeDelegationScopeType.ALL;

    @Column(name = "module_aliases", type = ColumnType.JSON_SET, comment = "Included module aliases")
    private Set<String> moduleAliases;

    @Column(name = "action_scope_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Action scope type", defaultVal = @Default(varchar = "all"))
    private EmployeeDelegationScopeType actionScopeType = EmployeeDelegationScopeType.ALL;

    @Column(name = "action_keys", type = ColumnType.JSON_SET, comment = "Included action keys")
    private Set<String> actionKeys;

    @Column(name = "memo", type = ColumnType.TEXT, comment = "Delegation memo")
    private String memo;

    @Column(name = "enabled", type = ColumnType.BOOLEAN, nullable = false, comment = "Enabled flag",
            defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean enabled = Boolean.TRUE;
}
