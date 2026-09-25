package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.formula.FormulaIssueLevel;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

@Getter
@Setter
@Table(name = "platform_module_metadata_formula_rule", comment = "Module metadata formula rule")
@CompositeIndex(columns = {"relation_id", "alias"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "relationId")
public class ModuleMetadataFormulaRule extends StandardEnabledSortableEntity {
    @Column(name = "relation_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Module metadata relation id")
    private String relationId;

    @Column(name = "alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Formula rule alias")
    private String alias;

    @Column(name = "rule_kind", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Formula rule kind")
    private FormulaRuleKind ruleKind;

    @Column(name = "rule_phase", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Formula rule phase")
    private FormulaRulePhase rulePhase;

    @Column(name = "target_field", type = ColumnType.VARCHAR, length = 128, comment = "Target field path")
    private String targetField;

    @Column(name = "expression", type = ColumnType.TEXT, nullable = false, comment = "Formula expression")
    @Required
    @NormalizeText
    private String expression;

    @Column(name = "severity", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Issue severity")
    private FormulaIssueLevel severity;

    @Column(name = "message_template", type = ColumnType.VARCHAR, length = 512, comment = "Issue message template")
    private String messageTemplate;

    @Column(name = "stop_on_error", comment = "Whether to stop after this rule fails")
    private Boolean stopOnError;
}
