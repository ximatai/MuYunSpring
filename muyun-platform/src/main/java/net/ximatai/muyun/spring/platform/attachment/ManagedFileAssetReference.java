package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Stable governed binding from a business file-reference field to one managed asset. */
@Table(name = "platform_managed_file_asset_reference", comment = "Managed file asset business reference")
@CompositeIndex(columns = {"tenant_id", "module_alias", "record_id", "field_name"})
@CompositeIndex(columns = {"tenant_id", "asset_id"})
public class ManagedFileAssetReference extends StandardEntity {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Business module alias")
    private String moduleAlias;

    @Column(name = "record_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Business record id")
    private String recordId;

    @Column(name = "field_name", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "File reference field name")
    private String fieldName;

    @Column(name = "asset_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Managed file asset id")
    private String assetId;

    public String getModuleAlias() { return moduleAlias; }
    public void setModuleAlias(String moduleAlias) { this.moduleAlias = moduleAlias; }
    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
}
