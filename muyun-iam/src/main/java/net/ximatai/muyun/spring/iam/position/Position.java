package net.ximatai.muyun.spring.iam.position;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrity;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;

@Getter
@Setter
@Table(name = "iam_position", comment = "Position")
@SortPartitionBy(fields = "categoryId", message = "Position sort can only move records within the same category")
@TenantUniqueConstraint(fields = "code")
@Required(fields = "title")
@NormalizeText(fields = "title")
public class Position extends StandardEnabledSortableEntity {
    @Column(name = "category_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Position category id")
    @ReferenceTo(target = PositionCategoryService.class,
            integrity = @ReferenceIntegrity(requireEnabled = true, onTargetUnavailable = ReferenceTargetUnavailablePolicy.RESTRICT))
    @Required
    @NormalizeText
    private String categoryId;

    @Column(name = "code", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Position code")
    @Required
    @NormalizeText
    private String code;

    @Column(name = "description", type = ColumnType.VARCHAR, length = 512, comment = "Description")
    @NormalizeText(TextNormalization.TRIM_TO_NULL)
    private String description;
}
