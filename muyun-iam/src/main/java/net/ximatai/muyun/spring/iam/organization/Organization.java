package net.ximatai.muyun.spring.iam.organization;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
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
}
