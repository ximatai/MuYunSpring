package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.child.Children;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
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

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "workbench_brand_mode", type = ColumnType.VARCHAR, length = 32,
            comment = "Workbench brand composition mode")
    private TenantWorkbenchBrandMode workbenchBrandMode;

    @Column(name = "workbench_title", type = ColumnType.VARCHAR, length = 100, comment = "Workbench brand primary title")
    private String workbenchTitle;

    @Column(name = "workbench_subtitle", type = ColumnType.VARCHAR, length = 200, comment = "Workbench brand secondary title")
    private String workbenchSubtitle;

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

    public TenantWorkbenchBrandMode getWorkbenchBrandMode() {
        return workbenchBrandMode;
    }

    public void setWorkbenchBrandMode(TenantWorkbenchBrandMode workbenchBrandMode) {
        this.workbenchBrandMode = workbenchBrandMode;
    }

    public String getWorkbenchTitle() {
        return workbenchTitle;
    }

    public void setWorkbenchTitle(String workbenchTitle) {
        this.workbenchTitle = workbenchTitle;
    }

    public String getWorkbenchSubtitle() {
        return workbenchSubtitle;
    }

    public void setWorkbenchSubtitle(String workbenchSubtitle) {
        this.workbenchSubtitle = workbenchSubtitle;
    }

}
