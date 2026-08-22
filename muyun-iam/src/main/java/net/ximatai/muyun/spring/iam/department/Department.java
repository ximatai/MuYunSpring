package net.ximatai.muyun.spring.iam.department;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrity;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledTreeEntity;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;

@Getter
@Setter
@Table(name = "iam_department", comment = "Department")
@SortPartitionBy(fields = "organizationId", message = "Department sort can only move records within the same organization")
@TenantUniqueConstraint(fields = {"organizationId", "code"})
@InitialDataFields(managed = {"organizationId", "code"}, operator = {"title", "enabled", "sortOrder", "parentId"})
public class Department extends StandardEnabledTreeEntity {
    @Column(name = "organization_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Organization id")
    @ReferenceTo(target = OrganizationService.class,
            integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.RESTRICT))
    private String organizationId;

    /** Stable read fact for detail, list and tree-node projection. */
    @ReferenceLoad(source = "organizationId", field = "title")
    private transient String organizationTitle;

    @Column(name = "code", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Department code")
    private String code;
}
