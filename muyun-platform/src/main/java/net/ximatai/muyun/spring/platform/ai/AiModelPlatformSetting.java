package net.ximatai.muyun.spring.platform.ai;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Global governance controls for tenant-owned AI model connections. */
@Getter
@Setter
@Table(name = "platform_ai_model_setting", comment = "AI model platform setting")
public class AiModelPlatformSetting extends StandardEntity {
    @Column(name = "tenant_registration_enabled", type = ColumnType.BOOLEAN, nullable = false,
            comment = "Whether tenant administrators may maintain their own AI model")
    private Boolean tenantRegistrationEnabled = Boolean.FALSE;
}
