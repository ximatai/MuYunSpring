package net.ximatai.muyun.spring.iam.position;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledTreeEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;

@Getter
@Setter
@Table(name = "iam_position_category", comment = "Position category")
@TenantUniqueConstraint(fields = "code")
@Required(fields = "title")
@NormalizeText(fields = "title")
public class PositionCategory extends StandardEnabledTreeEntity {
    @Column(name = "code", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Position category code")
    @Required
    @NormalizeText
    private String code;

    @Column(name = "description", type = ColumnType.VARCHAR, length = 512, comment = "Description")
    @NormalizeText(TextNormalization.TRIM_TO_NULL)
    private String description;
}
