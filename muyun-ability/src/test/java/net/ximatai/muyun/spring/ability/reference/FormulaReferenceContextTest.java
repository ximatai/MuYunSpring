package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition;
import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRuleExecutionPlan;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import net.ximatai.muyun.spring.common.formula.FormulaValueType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaReferenceContextTest {
    private static final ReferenceTarget ORDER = ReferenceTarget.of("sales", "order");
    private static final ReferenceTarget SUPPLIER = ReferenceTarget.of("crm", "supplier");
    private static final ReferenceTarget REGION = ReferenceTarget.of("crm", "region");

    @Test
    void shouldReadDeclaredMultiHopReferenceAndRereadAfterRootCalculation() {
        ReferenceTargetResolver resolver = resolver();
        List<FormulaRule> rules = List.of(
                new FormulaRule("changeSupplier", "{supplierId} = 's2'", FormulaRuleKind.CALCULATION,
                        FormulaRulePhase.BEFORE_SAVE, "supplierId"),
                new FormulaRule("derive", "{amount} * {supplierId.regionId.rate}", FormulaRuleKind.CALCULATION,
                        FormulaRulePhase.BEFORE_SAVE, "total"));
        FormulaReferenceContext references = FormulaReferenceContext.compile(ORDER, rules, resolver);
        List<FormulaFieldDefinition> fields = new java.util.ArrayList<>(List.of(
                FormulaFieldDefinition.of("supplierId", FormulaValueType.STRING),
                FormulaFieldDefinition.of("amount", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("total", FormulaValueType.DECIMAL)));
        fields.addAll(references.fields());
        Map<String, Object> values = new LinkedHashMap<>(Map.of("supplierId", "s1", "amount", BigDecimal.TEN, "total", 0));

        FormulaRuleExecutionPlan.forMainRecord(rules, fields).execute(new net.ximatai.muyun.spring.common.formula.FormulaEngine(),
                FormulaRuntimeData.typed(values, Map.of(), fields, references.paths(), references::resolve));

        assertThat(values.get("supplierId")).isEqualTo("s2");
        assertThat((BigDecimal) values.get("total")).isEqualByComparingTo("70");
    }

    @Test
    void shouldRejectManyRootAndReferenceWrites() {
        FormulaReferenceContext context = FormulaReferenceContext.compile(ORDER, List.of(
                new FormulaRule("derive", "{supplierId.regionId.rate}", FormulaRuleKind.CALCULATION,
                        FormulaRulePhase.BEFORE_SAVE, "total")), resolver());
        assertThatThrownBy(() -> context.resolve(Map.of("supplierId", List.of("s1", "s2"))))
                .hasMessageContaining("one id");

        List<FormulaFieldDefinition> fields = new java.util.ArrayList<>(context.fields());
        fields.add(FormulaFieldDefinition.of("total", FormulaValueType.DECIMAL));
        assertThat(new net.ximatai.muyun.spring.common.formula.FormulaEngine().execute(List.of(
                        new FormulaRule("write", "{supplierId.regionId.rate} = 1", FormulaRuleKind.CALCULATION,
                                FormulaRulePhase.BEFORE_SAVE, null)),
                FormulaRuntimeData.typed(new LinkedHashMap<>(Map.of("total", 0)), Map.of(), fields,
                        context.paths(), context::resolve)).report().errors())
                .extracting(issue -> issue.code()).contains("FORMULA_REFERENCE_FIELD_READ_ONLY");
    }

    private static ReferenceTargetResolver resolver() {
        return new ReferenceTargetResolver() {
            @Override public Optional<ReferenceAbility<?>> resolve(ReferenceTarget target) {
                return Optional.of(new ReferenceAbility<TargetRecord>() {
                    @Override public net.ximatai.muyun.spring.ability.BaseDao<TargetRecord, String> getDao() { return null; }
                    @Override public String getModuleAlias() { return target.qualifiedName(); }
                    @Override public Map<String, Map<String, Object>> projections(java.util.Collection<String> ids,
                                                                                   java.util.Collection<String> names) {
                        String id = ids.iterator().next();
                        if (SUPPLIER.equals(target)) return Map.of(id, Map.of("regionId", id.equals("s2") ? "r2" : "r1"));
                        if (REGION.equals(target)) return Map.of(id, Map.of("rate", id.equals("r2") ? 7 : 3));
                        return Map.of();
                    }
                });
            }
            @Override public Optional<ReferencePlan> referencePlan(ReferenceTarget source, String field) {
                if (ORDER.equals(source) && field.equals("supplierId")) return Optional.of(ReferencePlan.of(field, SUPPLIER, ReferenceCardinality.ONE));
                if (SUPPLIER.equals(source) && field.equals("regionId")) return Optional.of(ReferencePlan.of(field, REGION, ReferenceCardinality.ONE));
                return Optional.empty();
            }
            @Override public Optional<FormulaValueType> formulaFieldType(ReferenceTarget target, String field) {
                if (ORDER.equals(target) && field.equals("supplierId")) return Optional.of(FormulaValueType.STRING);
                if (SUPPLIER.equals(target) && field.equals("regionId")) return Optional.of(FormulaValueType.STRING);
                return REGION.equals(target) && field.equals("rate") ? Optional.of(FormulaValueType.DECIMAL) : Optional.empty();
            }
        };
    }

    private static final class TargetRecord extends StandardEntity implements TitledCapable {
        @Override public String getTitle() { return "target"; }
    }
}
