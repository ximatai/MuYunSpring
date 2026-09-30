package net.ximatai.muyun.spring.platform.application;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Id;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.spring.common.model.capability.PlatformManagedCapable;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

@Table(name = "platform_application", comment = "Platform application")
@InitialDataFields(operator = {"title", "enabled", "sortOrder"})
@Getter
@Setter
public class Application extends StandardEnabledSortableEntity implements PlatformManagedCapable {
    @net.ximatai.muyun.spring.common.model.constraint.FieldPattern(net.ximatai.muyun.spring.common.util.PlatformNameRules.IDENTIFIER_PATTERN)
    @Id
    @Column(name = "id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Application alias")
    private String id;

    @Column(name = "system_managed", comment = "Whether application is managed by platform",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean systemManaged = Boolean.FALSE;

    public String getAlias() {
        return getId();
    }

    public void setAlias(String alias) {
        setId(alias);
    }
}
