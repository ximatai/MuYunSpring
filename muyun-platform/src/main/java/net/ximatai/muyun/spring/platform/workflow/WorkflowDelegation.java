package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

import java.util.Set;

@Getter
@Setter
@Table(name = "platform_workflow_delegation", comment = "Workflow delegation policy")
@CompositeIndex(columns = {"tenant_id", "principal_user_id", "enabled"})
@CompositeIndex(columns = {"tenant_id", "delegate_user_id", "enabled"})
@Required(fields = "title")
@NormalizeText(fields = "title")
public class WorkflowDelegation extends StandardEnabledSortableEntity {
    @Column(name = "title", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Delegation title")
    private String title;

    @Column(name = "principal_user_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Principal user id")
    @Required
    @NormalizeText
    private String principalUserId;

    @Column(name = "delegate_user_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Delegate user id")
    @Required
    @NormalizeText
    private String delegateUserId;

    @Column(name = "principal_can_process", type = ColumnType.BOOLEAN, nullable = false,
            comment = "Whether principal keeps process permission", defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean principalCanProcess = Boolean.FALSE;

    @Column(name = "module_scope_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Module scope type", defaultVal = @Default(varchar = "all"))
    private WorkflowDelegationScopeType moduleScopeType = WorkflowDelegationScopeType.ALL;

    @Column(name = "module_aliases", type = ColumnType.JSON_SET, comment = "Included module aliases")
    private Set<String> moduleAliases;

    @Column(name = "org_scope_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Organization scope type", defaultVal = @Default(varchar = "all"))
    private WorkflowDelegationScopeType orgScopeType = WorkflowDelegationScopeType.ALL;

    @Column(name = "org_ids", type = ColumnType.JSON_SET, comment = "Included organization ids")
    private Set<String> orgIds;

    @Column(name = "memo", type = ColumnType.TEXT, comment = "Delegation memo")
    private String memo;
}
