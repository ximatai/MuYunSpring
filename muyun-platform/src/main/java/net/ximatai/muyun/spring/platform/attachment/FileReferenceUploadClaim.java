package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Permanent file-id reservation; detailed, expiring upload admissions are retained separately. */
@Table(name = "platform_file_reference_upload_claim", comment = "Exclusive file upload registration claim")
public class FileReferenceUploadClaim extends StandardEntity {
    @Column(name = "registration_id", type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String registrationId;
    public String getRegistrationId() { return registrationId; }
    public void setRegistrationId(String value) { registrationId = value; }
}
