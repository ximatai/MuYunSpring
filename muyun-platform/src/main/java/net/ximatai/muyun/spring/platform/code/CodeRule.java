package net.ximatai.muyun.spring.platform.code;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Table(name = "platform_code_rule", comment = "Platform code rule")
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = {"moduleAlias", "entityAlias"})
public class CodeRule extends StandardEnabledSortableEntity {
    @Indexed
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Module alias")
    private String moduleAlias;

    @Indexed
    @Column(name = "entity_alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Entity alias")
    private String entityAlias;

    @Indexed
    @Column(name = "module_metadata_field_id", type = ColumnType.VARCHAR, length = 32, comment = "Module metadata field id")
    private String moduleMetadataFieldId;

    @Indexed
    @Column(name = "metadata_field_id", type = ColumnType.VARCHAR, length = 64, comment = "Metadata field id")
    private String metadataFieldId;

    @Column(name = "field_name", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Target field name")
    private String fieldName;

    @Column(name = "field_role", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Code field role",
            defaultVal = @Default(varchar = "NORMAL"))
    private CodeFieldRole fieldRole = CodeFieldRole.NORMAL;

    /** Draft intent before first activation; calibrated from the shared field declaration afterwards. */
    @Column(name = "field_trigger", type = ColumnType.VARCHAR, length = 32, comment = "Code field trigger snapshot")
    private net.ximatai.muyun.spring.ability.code.CodeTrigger fieldTrigger;

    @Column(name = "mode", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Code mode",
            defaultVal = @Default(varchar = "AUTO"))
    private CodeMode mode = CodeMode.AUTO;

    @Column(name = "org_scope_type", type = ColumnType.VARCHAR, length = 32, comment = "Organization scope type")
    private CodeOrgScopeType orgScopeType = CodeOrgScopeType.GLOBAL;

    @Indexed
    @Column(name = "org_scope_id", type = ColumnType.VARCHAR, length = 64, comment = "Organization scope id")
    private String orgScopeId;

    @Column(name = "global_default", type = ColumnType.BOOLEAN, comment = "Whether this is the global default rule",
            defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean globalDefault = Boolean.TRUE;

    @Indexed
    @Column(name = "effective_from", type = ColumnType.TIMESTAMP, comment = "Effective from")
    private LocalDateTime effectiveFrom;

    @Indexed
    @Column(name = "effective_to", type = ColumnType.TIMESTAMP, comment = "Effective to")
    private LocalDateTime effectiveTo;

    @Column(name = "linked_update", type = ColumnType.BOOLEAN, comment = "Whether code can be recalculated on update",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean linkedUpdate = Boolean.FALSE;

    @Column(name = "allow_recycle", type = ColumnType.BOOLEAN, comment = "Whether old codes may be recycled",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean allowRecycle = Boolean.FALSE;

    private transient List<CodeRuleSegment> segments = new ArrayList<>();

    private transient CodeSequencePolicy sequencePolicy;
}
