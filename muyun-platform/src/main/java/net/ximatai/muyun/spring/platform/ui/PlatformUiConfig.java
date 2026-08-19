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
@Table(name = "platform_ui_config", comment = "Platform low-code UI config")
@CompositeIndex(columns = {"ui_set_id", "client_type"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "uiSetId")
public class PlatformUiConfig extends StandardEnabledSortableEntity {
    @Column(name = "ui_set_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "UI set id")
    private String uiSetId;

    @Column(name = "client_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Client type")
    private PlatformUiClientType clientType = PlatformUiClientType.WEB;

    @Column(name = "layout_json", type = ColumnType.TEXT, comment = "Layout JSON")
    private String layoutJson;

    @Column(name = "published", type = ColumnType.BOOLEAN, comment = "Published config flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean published = Boolean.FALSE;
}
