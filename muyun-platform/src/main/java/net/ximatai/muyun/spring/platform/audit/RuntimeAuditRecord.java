package net.ximatai.muyun.spring.platform.audit;

import net.ximatai.muyun.spring.common.model.constraint.WriteOperation;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;

@Getter
@Setter
@Table(name = "platform_runtime_audit_record", comment = "Runtime audit record")
@CompositeIndex(columns = {"event_id"}, unique = true)
@CompositeIndex(columns = {"trace_id"})
@CompositeIndex(columns = {"tenant_id", "module_alias", "entity_alias", "record_id"})
@CompositeIndex(columns = {"event_type", "occurred_at"})
@CompositeIndex(columns = {"tenant_id", "action_code", "occurred_at"})
@CompositeIndex(columns = {"tenant_id", "result_type", "occurred_at"})
@CompositeIndex(columns = {"tenant_id", "acting_delegation_id", "occurred_at"})
public class RuntimeAuditRecord extends StandardEntity {
    @Column(name = "event_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Runtime event id")
    @Required(on = WriteOperation.INSERT)
    private String eventId;

    @Column(name = "trace_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Runtime trace id")
    @Required(on = WriteOperation.INSERT)
    private String traceId;

    @Column(name = "event_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Runtime event type")
    @Required(on = WriteOperation.INSERT)
    private RuntimeEventType eventType;

    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Module alias")
    @Required(on = WriteOperation.INSERT)
    private String moduleAlias;

    @Column(name = "entity_alias", type = ColumnType.VARCHAR, length = 64, comment = "Entity alias")
    private String entityAlias;

    @Column(name = "record_id", type = ColumnType.VARCHAR, length = 64, comment = "Record id")
    private String recordId;

    @Column(name = "action_code", type = ColumnType.VARCHAR, length = 64, comment = "Action code")
    private String actionCode;

    @Column(name = "executor_type", type = ColumnType.VARCHAR, length = 32, comment = "Action executor type")
    private String executorType;

    @Column(name = "action_level", type = ColumnType.VARCHAR, length = 32, comment = "Action level")
    private String actionLevel;

    @Column(name = "result_type", type = ColumnType.VARCHAR, length = 32, comment = "Action result type")
    private String resultType;

    @Column(name = "result_message", type = ColumnType.TEXT, comment = "Action result message")
    private String resultMessage;

    @Column(name = "refresh_requested", type = ColumnType.BOOLEAN, comment = "Action result refresh flag")
    private Boolean refreshRequested;

    @Column(name = "redirect_to", type = ColumnType.TEXT, comment = "Action result redirect target")
    private String redirectTo;

    @Column(name = "result_text", type = ColumnType.TEXT, comment = "Simple action result text")
    private String resultText;

    @Column(name = "failure_stage", type = ColumnType.VARCHAR, length = 64, comment = "Action failure stage")
    private String failureStage;

    @Column(name = "error_message", type = ColumnType.TEXT, comment = "Action failure message")
    private String errorMessage;

    @Column(name = "error_type", type = ColumnType.VARCHAR, length = 256, comment = "Action failure error type")
    private String errorType;

    @Column(name = "system_context", type = ColumnType.BOOLEAN, nullable = false, comment = "System context flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean systemContext = Boolean.FALSE;

    @Column(name = "system_reason", type = ColumnType.VARCHAR, length = 256, comment = "System context reason")
    private String systemReason;

    @Column(name = "operator_id", type = ColumnType.VARCHAR, length = 64, comment = "Runtime operator id")
    private String operatorId;

    @Column(name = "operator_type", type = ColumnType.VARCHAR, length = 32, comment = "Runtime operator type")
    private String operatorType;

    @Column(name = "authorization_decision", type = ColumnType.VARCHAR, length = 32, comment = "Authorization decision")
    private String authorizationDecision;

    @Column(name = "authorization_permission_code", type = ColumnType.VARCHAR, length = 192,
            comment = "Authorization permission code")
    private String authorizationPermissionCode;

    @Column(name = "authorization_permission_action_code", type = ColumnType.VARCHAR, length = 64,
            comment = "Authorization permission action code")
    private String authorizationPermissionActionCode;

    @Column(name = "acting_delegation_id", type = ColumnType.VARCHAR, length = 64, comment = "Acting delegation id")
    private String actingDelegationId;

    @Column(name = "acting_principal_user_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Acting principal user id")
    private String actingPrincipalUserId;

    @Column(name = "acting_principal_employee_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Acting principal employee id")
    private String actingPrincipalEmployeeId;

    @Column(name = "acting_principal_organization_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Acting principal organization id")
    private String actingPrincipalOrganizationId;

    @Column(name = "acting_principal_department_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Acting principal department id")
    private String actingPrincipalDepartmentId;

    @Column(name = "acting_principal_employee_position_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Acting principal employee position id")
    private String actingPrincipalEmployeePositionId;

    @Column(name = "mutation_source", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Mutation source")
    @Required(on = WriteOperation.INSERT)
    private RuntimeMutationSource mutationSource;

    @Column(name = "payload_text", type = ColumnType.TEXT, comment = "Runtime event payload snapshot")
    private String payloadText;

    @Column(name = "occurred_at", type = ColumnType.TIMESTAMP, nullable = false, comment = "Runtime event occurred at")
    @Required(on = WriteOperation.INSERT)
    private Instant occurredAt;
}
