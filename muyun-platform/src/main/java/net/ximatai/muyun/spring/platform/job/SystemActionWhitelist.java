package net.ximatai.muyun.spring.platform.job;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_system_action_whitelist", comment = "Scheduled system action whitelist")
@CompositeIndex(columns = {"module_alias", "action_code"}, unique = true)
public class SystemActionWhitelist extends StandardEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "moduleAlias")
    private String moduleAlias;
    @Column(name = "action_code", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "actionCode")
    private String actionCode;
}
