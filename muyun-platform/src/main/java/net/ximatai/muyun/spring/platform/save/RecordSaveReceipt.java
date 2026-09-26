package net.ximatai.muyun.spring.platform.save;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Committed save identity; business values remain in their authoritative business record. */
@Getter
@Setter
@Table(name = "platform_record_save_receipt", comment = "Confirmed record save receipt")
public class RecordSaveReceipt extends StandardEntity {
    @Column(type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Scoped request identity digest")
    private String requestDigest;
    @Column(type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Reviewed request fingerprint")
    private String payloadDigest;
    @Column(type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Saved module")
    private String moduleAlias;
    @Column(type = ColumnType.VARCHAR, length = 16, nullable = false, comment = "Create or update")
    private String actionCode;
    @Column(type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Saved record identity")
    private String recordId;
    @Column(type = ColumnType.INT, comment = "Committed record version")
    private Integer recordVersion;
}
