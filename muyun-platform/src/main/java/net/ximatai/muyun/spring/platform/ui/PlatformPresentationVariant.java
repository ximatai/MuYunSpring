package net.ximatai.muyun.spring.platform.ui;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;

/**
 * One client- and scope-specific composition of a page definition.
 *
 * <p>The first delivery stores complete variants. It intentionally has no inheritance or patch
 * semantics; those would make preview, publish and rollback opaque before there is evidence that
 * they are needed.</p>
 */
@Getter
@Setter
@Table(name = "platform_presentation_variant", comment = "Platform page presentation variant")
@CompositeIndex(columns = {"page_id", "client_type", "scope_type", "tenant_id", "organization_id"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "pageId")
public class PlatformPresentationVariant extends StandardEnabledSortableEntity {
    @Column(name = "page_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Page definition id")
    private String pageId;

    @Column(name = "client_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Target client type")
    @OptionField(type = OptionSourceType.ENUM, enumType = PlatformPresentationClientType.class)
    private PlatformPresentationClientType clientType = PlatformPresentationClientType.WEB;

    @Column(name = "scope_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Presentation scope type")
    @OptionField(type = OptionSourceType.ENUM, enumType = PlatformPresentationScopeType.class)
    private PlatformPresentationScopeType scopeType = PlatformPresentationScopeType.GLOBAL;

    @Column(name = "organization_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Organization presentation scope")
    private String organizationId;

}
