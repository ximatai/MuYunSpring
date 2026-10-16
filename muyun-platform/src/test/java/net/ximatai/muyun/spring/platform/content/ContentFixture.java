package net.ximatai.muyun.spring.platform.content;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter @Setter
@Table(name = "content_fixture", comment = "Business content contract fixture")
public class ContentFixture extends StandardEntity {
    @BusinessContent(value = ContentPolicy.MANUAL_TRANSLATIONS, key = "category-name")
    @Column(name = "title", type = ColumnType.TEXT)
    private String title;
    @Column(name = "quantity", type = ColumnType.INT)
    private Integer quantity;
}
