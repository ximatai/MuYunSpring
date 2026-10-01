package net.ximatai.muyun.spring.platform.ui;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Original composition input committed atomically with its named fields and published revision. */
@Getter
@Setter
@Table(name = "platform_page_composition_receipt", comment = "Page composition publication receipt")
public class PageCompositionSaveReceipt extends StandardEntity {
    @Column(type = ColumnType.VARCHAR, length = 64, nullable = false)
    private String requestDigest;
    @Column(type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String revisionId;
}
