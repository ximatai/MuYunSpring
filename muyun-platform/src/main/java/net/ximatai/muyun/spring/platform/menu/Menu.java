package net.ximatai.muyun.spring.platform.menu;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Id;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledTreeEntity;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.capability.PlatformManagedCapable;

@Getter
@Setter
@Table(name = "platform_menu", comment = "Platform menu")
@InitialDataFields(
        identity = {"schemeId"},
        managed = {"parentId", "openMode", "moduleAlias", "route", "externalUrl", "pageMode", "systemManaged",
                "defaultUiConfigId", "defaultQueryTemplateId", "entryParamsJson"},
        operator = {"title", "enabled", "sortOrder"}
)
public class Menu extends StandardEnabledTreeEntity implements PlatformManagedCapable {
    @Id
    @Column(name = "id", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Menu id")
    private String id;

    @Column(name = "scheme_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Menu scheme id")
    private String schemeId;

    @Column(name = "open_mode", type = ColumnType.VARCHAR, length = 32, comment = "Menu open mode")
    private MenuOpenMode openMode;

    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, comment = "Target module alias")
    private String moduleAlias;

    @Column(name = "route", type = ColumnType.VARCHAR, length = 256, comment = "Route path")
    private String route;

    @Column(name = "external_url", type = ColumnType.VARCHAR, length = 512, comment = "External url")
    private String externalUrl;

    @Column(name = "page_mode", type = ColumnType.VARCHAR, length = 32, comment = "Low-code page mode")
    private MenuPageMode pageMode;

    @Column(name = "default_ui_config_id", type = ColumnType.VARCHAR, length = 32, comment = "Default UI config id")
    private String defaultUiConfigId;

    @Column(name = "default_query_template_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Default query template id")
    private String defaultQueryTemplateId;

    @Column(name = "entry_params_json", type = ColumnType.TEXT, comment = "Entry params JSON")
    private String entryParamsJson;

    /** Whether this system-scope menu is a code-declared platform baseline. */
    @Column(name = "system_managed", type = ColumnType.BOOLEAN, comment = "System managed menu baseline")
    private Boolean systemManaged;

    /** Whether platform startup reconciliation owns the managed routing fields of this copy. */
    @Column(name = "platform_managed", type = ColumnType.BOOLEAN, comment = "Platform managed menu copy")
    private Boolean platformManaged;

    /** Source fingerprint last applied by platform reconciliation; useful for audit and diagnostics. */
    @Column(name = "platform_managed_revision", type = ColumnType.VARCHAR, length = 64,
            comment = "Platform managed revision")
    private String platformManagedRevision;
}
