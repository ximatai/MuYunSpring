package net.ximatai.muyun.spring.common.model.standard;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.capability.EnabledCapable;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;

/** Standard base class for titled entities that can be enabled or disabled. */
@Getter
@Setter
public abstract class StandardEnabledEntity extends StandardTitledEntity implements EnabledCapable {
    @Column(name = PlatformAbilityFields.ENABLED_COLUMN, type = ColumnType.BOOLEAN, comment = "Enabled flag",
            defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean enabled = Boolean.TRUE;
}
