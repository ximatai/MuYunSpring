package net.ximatai.muyun.spring.platform.ui;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;

@Getter
@Setter
@Table(name = "platform_presentation_revision", comment = "Platform page presentation revision")
@CompositeIndex(columns = {"variant_id", "revision_no"}, unique = true)
@CompositeIndex(columns = {"variant_id", "status"})
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "variantId")
public class PlatformPresentationRevision extends StandardEnabledSortableEntity {
    @Column(name = "variant_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Presentation variant id")
    private String variantId;

    @Column(name = "revision_no", type = ColumnType.INT, nullable = false, comment = "Revision number")
    private Integer revisionNo;

    @Column(name = "template_alias", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "UI template alias")
    private String templateAlias;

    @Column(name = "template_version", type = ColumnType.INT, nullable = false,
            comment = "UI template version", defaultVal = @Default(number = 1))
    private Integer templateVersion = 1;

    @Column(name = "ui_tree_json", type = ColumnType.TEXT, comment = "UI composition tree JSON")
    private String uiTreeJson;

    @Column(name = "status", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Revision lifecycle status", defaultVal = @Default(varchar = "draft"))
    @OptionField(type = OptionSourceType.ENUM, enumType = PlatformPresentationRevisionStatus.class)
    private PlatformPresentationRevisionStatus status = PlatformPresentationRevisionStatus.DRAFT;
}
