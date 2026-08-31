package net.ximatai.muyun.spring.ability;


import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.title.TitleField;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;

@Getter
@Setter
final class DemoCustomTitleRecord extends StandardEntity implements TitledCapable {
    private static final String INTERNAL_TEST_ONLY = "internal";

    @Column(name = "code", type = ColumnType.VARCHAR, unique = true)
    private String code;
    private String title;
    @TitleField
    private String displayName;

    DemoCustomTitleRecord(String title, String displayName) {
        this(null, title, displayName);
    }

    DemoCustomTitleRecord(String code, String title, String displayName) {
        this.code = code;
        this.title = title;
        this.displayName = displayName;
    }
}
