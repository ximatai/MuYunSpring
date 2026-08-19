package net.ximatai.muyun.spring.platform.module;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Id;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.capability.PlatformManagedCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledTreeEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.platform.application.ApplicationService;

@Getter
@Setter
@Table(name = "platform_module", comment = "Platform module")
@net.ximatai.muyun.spring.ability.SortPartitionBy(
        fields = "applicationAlias", message = "Module sort can only move records within the same application")
public class PlatformModule extends StandardEnabledTreeEntity implements PlatformManagedCapable {
    @Id
    @Column(name = "id", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Module alias")
    private String id;

    @Column(name = "parent_id", type = ColumnType.VARCHAR, length = 128, comment = "Parent module alias")
    private String parentId;

    @Column(name = "application_alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Application alias")
    @ReferenceTo(target = ApplicationService.class)
    private String applicationAlias;

    @Column(name = "module_kind", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Module kind",
            defaultVal = @Default(varchar = "static"))
    @OptionField(type = OptionSourceType.ENUM)
    private ModuleKind moduleKind = ModuleKind.STATIC;

    @Column(name = "entry_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Module entry type",
            defaultVal = @Default(varchar = "module"))
    @OptionField(type = OptionSourceType.ENUM)
    private ModuleEntryType entryType = ModuleEntryType.MODULE;

    @Column(name = "entry_route", type = ColumnType.VARCHAR, length = 256, comment = "Internal route entry")
    private String entryRoute;

    @Column(name = "entry_external_url", type = ColumnType.VARCHAR, length = 512, comment = "External link entry")
    private String entryExternalUrl;

    @Column(name = "system_managed", comment = "Whether module is managed by platform",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean systemManaged = Boolean.FALSE;

    public String getAlias() {
        return getId();
    }

    public void setAlias(String alias) {
        setId(alias);
    }
}
