package net.ximatai.muyun.spring.platform.metadata;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.reference.ReferenceSummary;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.FieldQueryDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;

import java.util.Set;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Table(name = "platform_field_spec", comment = "Platform field type")
@CompositeIndex(columns = {"alias"}, unique = true)
public class FieldSpec extends StandardEnabledSortableEntity {
    @Column(name = "alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Field spec alias")
    private String alias;

    @Column(name = "title", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Field spec title")
    private String title;

    @Column(name = "field_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Runtime field type")
    @OptionField(type = OptionSourceType.ENUM)
    private FieldType fieldType;

    @Column(name = "default_length", type = ColumnType.INT, comment = "Default length")
    private Integer defaultLength;

    @Column(name = "default_precision", type = ColumnType.INT, comment = "Default decimal precision")
    private Integer defaultPrecision;

    @Column(name = "default_scale", type = ColumnType.INT, comment = "Default decimal scale")
    private Integer defaultScale;

    @Column(name = "default_query_operator", type = ColumnType.VARCHAR, length = 32, comment = "Default query operator")
    @OptionField(type = OptionSourceType.ENUM)
    private DynamicQueryOperator defaultQueryOperator;

    @Column(name = "query_operators", type = ColumnType.JSON_SET, comment = "Allowed query operators")
    @OptionField(type = OptionSourceType.ENUM, enumType = DynamicQueryOperator.class,
            selectionMode = OptionSelectionMode.MULTIPLE)
    private Set<String> queryOperators;

    @Column(name = "default_ui_control_alias", type = ColumnType.VARCHAR, length = 64, comment = "Default field UI control alias")
    @ReferenceTo(target = FieldUiControlService.class)
    private String defaultUiControlAlias;

    @ReferenceSummary(source = "defaultUiControlAlias", fields = {"title", "alias"})
    private transient Map<String, Object> defaultUiControlSummary;

    @Column(name = "ui_control_aliases", type = ColumnType.JSON_SET, comment = "Allowed field UI control aliases")
    @ReferenceTo(target = FieldUiControlService.class, cardinality = ReferenceCardinality.MANY)
    private Set<String> uiControlAliases;

    @ReferenceSummary(source = "uiControlAliases", fields = {"title", "alias"})
    private transient List<Map<String, Object>> uiControlSummaries;

    public FieldQueryDefinition queryDefinition() {
        if (defaultQueryOperator == null && (queryOperators == null || queryOperators.isEmpty())) {
            return FieldQueryDefinition.disabled();
        }
        DynamicQueryOperator operator = defaultQueryOperator == null
                ? DynamicQueryOperator.defaultOperator(fieldType)
                : defaultQueryOperator;
        Set<DynamicQueryOperator> operators = queryOperators == null || queryOperators.isEmpty()
                ? DynamicQueryOperator.defaultOperators(fieldType)
                : DynamicQueryOperator.parseNames(queryOperators);
        return FieldQueryDefinition.enabled(fieldType, operator, operators);
    }
}
