package net.ximatai.muyun.spring.iam.organization;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;
import net.ximatai.muyun.spring.common.option.DictionaryField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledTreeEntity;

@Getter
@Setter
@Table(name = "iam_organization", comment = "Organization")
@TenantUniqueConstraint(fields = "code")
@InitialDataFields(managed = {"code"}, operator = {"title", "enabled", "sortOrder", "parentId"})
public class Organization extends StandardEnabledTreeEntity {
    @Column(name = "code", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Organization code")
    @Required
    @NormalizeText
    private String code;

    /** Generic organization classification; projects define their own types in the tenant dictionary. */
    @DictionaryField(
            source = "iam.organization_type",
            title = "机构类型",
            sortOrder = 20,
            initialItems = {
                    @DictionaryField.InitialItem(code = "company", title = "公司", sortOrder = 10),
                    @DictionaryField.InitialItem(code = "branch", title = "分支机构", sortOrder = 20),
                    @DictionaryField.InitialItem(code = "division", title = "事业部", sortOrder = 30)
            }
    )
    @Column(name = "organization_type", type = ColumnType.VARCHAR, length = 64, comment = "Organization type")
    @NormalizeText(TextNormalization.TRIM_TO_NULL)
    private String organizationType;

    @OptionLoad(source = "organizationType")
    private transient String organizationTypeTitle;

    @Column(name = "manager_employee_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Responsible employee")
    @net.ximatai.muyun.spring.ability.reference.ReferenceTo(target = net.ximatai.muyun.spring.iam.employee.EmployeeService.class)
    private String managerEmployeeId;

}
