package net.ximatai.muyun.spring.platform.application;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_application_construction_plan", comment = "Application construction requirements")
public class ApplicationConstructionPlan extends StandardEntity {
    @Indexed
    @Column(name = "owner_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Owner identity")
    private String ownerId;
    @Column(name = "title", type = ColumnType.VARCHAR, length = 120, nullable = false, comment = "Business plan title")
    private String title;
    @Column(name = "content_json", type = ColumnType.TEXT, nullable = false, comment = "Current confirmed requirements")
    private String contentJson;
}
