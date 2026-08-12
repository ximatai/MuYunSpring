package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.child.Children;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;

import java.util.List;

@Table(name = "iam_tenant", comment = "Tenant")
@InitialDataFields(operator = {"title", "enabled", "sortOrder"})
public class Tenant extends StandardEnabledSortableEntity {
    @FileReference(allowedMediaTypes = {"image/png", "image/jpeg", "image/gif", "image/webp"},
            maxFileSizeBytes = 524288, storagePolicy = FileReferenceStoragePolicy.DATABASE_INLINE)
    @Column(name = "light_logo_asset_id", type = ColumnType.VARCHAR, length = 32, comment = "Light logo managed file asset id")
    private String lightLogoAssetId;

    @FileReference(allowedMediaTypes = {"image/png", "image/jpeg", "image/gif", "image/webp"},
            maxFileSizeBytes = 524288, storagePolicy = FileReferenceStoragePolicy.DATABASE_INLINE)
    @Column(name = "dark_logo_asset_id", type = ColumnType.VARCHAR, length = 32, comment = "Dark logo managed file asset id")
    private String darkLogoAssetId;

    @Children
    private List<TenantApplication> applications;

    public String getAlias() {
        return getId();
    }

    public void setAlias(String alias) {
        setId(alias);
    }

    public String getLightLogoAssetId() {
        return lightLogoAssetId;
    }

    public void setLightLogoAssetId(String lightLogoAssetId) {
        this.lightLogoAssetId = lightLogoAssetId;
    }

    public String getDarkLogoAssetId() {
        return darkLogoAssetId;
    }

    public void setDarkLogoAssetId(String darkLogoAssetId) {
        this.darkLogoAssetId = darkLogoAssetId;
    }

}
