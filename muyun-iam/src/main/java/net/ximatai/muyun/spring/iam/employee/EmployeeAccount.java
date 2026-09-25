package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrity;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.iam.user.UserAccountService;

@Getter
@Setter
@Table(name = "iam_employee_account", comment = "Employee account binding")
@CompositeIndex(columns = {"tenant_id", "employee_id"}, unique = true)
@CompositeIndex(columns = {"tenant_id", "user_id"}, unique = true)
@InitialDataFields(
        managed = {"employeeId", "userId"}
)
public class EmployeeAccount extends StandardEntity {
    @Column(name = "employee_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Employee id")
    @ReferenceTo(target = EmployeeService.class,
            integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.CASCADE_DELETE))
    @Required
    @NormalizeText
    private String employeeId;

    @Column(name = "user_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "User account id")
    @ReferenceTo(target = UserAccountService.class,
            integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.CASCADE_DELETE))
    @Required
    @NormalizeText
    private String userId;
}
