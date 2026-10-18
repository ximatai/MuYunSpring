package net.ximatai.muyun.spring.platform.content;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter @Setter
@Table(name = "content_generation_fixture", comment = "Automatic business reading contract fixture")
public class ContentGenerationFixture extends StandardEntity {
    @Column(name = "project_id", type = ColumnType.VARCHAR, length = 64)
    private String projectId;

    @BusinessContent(value = ContentPolicy.AUTO_TRANSLATE, key = "quality-description")
    @Column(name = "description", type = ColumnType.TEXT)
    private String description;

    @BusinessContent(value = ContentPolicy.MANUAL_TRANSLATIONS, key = "manual-name")
    @Column(name = "manual_name", type = ColumnType.TEXT)
    private String manualName;
}
