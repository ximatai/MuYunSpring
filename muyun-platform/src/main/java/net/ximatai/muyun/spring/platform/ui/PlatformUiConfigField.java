package net.ximatai.muyun.spring.platform.ui;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

@Getter
@Setter
@Table(name = "platform_ui_config_field", comment = "Platform low-code UI config field")
@CompositeIndex(columns = {"ui_config_id", "module_metadata_field_id"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "uiConfigId")
public class PlatformUiConfigField extends StandardEnabledSortableEntity {
    @Column(name = "ui_config_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "UI config id")
    private String uiConfigId;

    @Column(name = "module_metadata_field_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Module metadata field id")
    private String moduleMetadataFieldId;

    @Column(name = "field_ui_control_alias", type = ColumnType.VARCHAR, length = 64, comment = "Field UI type alias")
    private String fieldUiControlAlias;

    @Column(name = "visible", type = ColumnType.BOOLEAN, comment = "Visible flag",
            defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean visible = Boolean.TRUE;

    @Column(name = "read_only", type = ColumnType.BOOLEAN, comment = "Read only flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean readOnly = Boolean.FALSE;

    @Column(name = "required_override", type = ColumnType.BOOLEAN, comment = "Required override")
    private Boolean requiredOverride;

    @Column(name = "placeholder", type = ColumnType.VARCHAR, length = 256, comment = "Placeholder")
    private String placeholder;

    @Column(name = "default_value", type = ColumnType.VARCHAR, length = 512, comment = "Default value")
    private String defaultValue;

    @Column(name = "width", type = ColumnType.INT, comment = "Display width")
    private Integer width;

    @Column(name = "max_display_lines", type = ColumnType.INT, comment = "Maximum display lines in list cells")
    private Integer maxDisplayLines;

    @Column(name = "column_span", type = ColumnType.INT, nullable = false, comment = "Form/detail column span",
            defaultVal = @Default(number = 1))
    private Integer columnSpan = 1;

    @Column(name = "align", type = ColumnType.VARCHAR, length = 16, comment = "Display align")
    private String align;

    @Column(name = "fixed_position", type = ColumnType.VARCHAR, length = 16, comment = "Fixed position")
    private PlatformUiFixedPosition fixedPosition;
}
