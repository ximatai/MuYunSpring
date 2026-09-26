package net.ximatai.muyun.spring.platform.application;

import org.junit.jupiter.api.Test;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import java.util.List;
import static net.ximatai.muyun.spring.platform.application.ApplicationConstructionRequirement.*;
import static net.ximatai.muyun.spring.platform.application.ApplicationConstructionRequirements.*;
import static org.assertj.core.api.Assertions.*;

class ApplicationConstructionRequirementsTest {
    private MetadataField field(String name, boolean required, boolean unique) {
        var field = new MetadataField();
        field.setFieldName(name); field.setRequired(required); field.setUniqueField(unique);
        return field;
    }
    private ApplicationConstructionPlanContent content(List<ApplicationConstructionRequirement> requirements) {
        return new ApplicationConstructionPlanContent("订单", "记录订单", List.of("实际记一单"), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("order", "订单", "登记")),
                List.of(), List.of("订单号必填且不重复"), List.of(), List.of(), List.of(), List.of("实际录入"), requirements);
    }
    private ApplicationConstructionRequirement scope() { return new ApplicationConstructionRequirement(Section.SCOPE, 0, "order", Mode.MANUAL, "", "用户实际录入核验，不是自动证明"); }
    @Test void missingAndUnsupportedRequirementsCannotBecomeCompletedByHavingAField() {
        assertThat(blocked(evaluate(content(null), "order", List.of()))).isTrue();
        var plan = content(List.of(scope(), new ApplicationConstructionRequirement(Section.RULE, 0, "order", Mode.UNSUPPORTED, "", "暂不支持，须商定范围")));
        assertThatThrownBy(() -> requireBuildable(plan, "order")).hasMessageContaining("分期范围");
        assertThat(blocked(evaluate(plan, "order", List.of(field("status", false, false))))).isTrue();
    }
    @Test void actualConstraintsAreEvidenceAndManualChecksNeverBecomeAutomaticProof() {
        var plan = content(List.of(scope(), new ApplicationConstructionRequirement(Section.RULE, 0, "order", Mode.REQUIRED, "number", "必填"),
                new ApplicationConstructionRequirement(Section.RULE, 0, "order", Mode.UNIQUE, "number", "防重复")));
        requireBuildable(plan, "order");
        assertThat(missingConfiguration(evaluate(plan, "order", List.of(field("number", true, false))))).isTrue();
        var evidence = evaluate(plan, "order", List.of(field("number", true, true)));
        assertThat(missingConfiguration(evidence)).isFalse();
        assertThat(evidence).extracting(Evidence::status).containsExactly(Status.MANUAL_CHECK_REQUIRED, Status.CONFIGURATION_MATCHED, Status.CONFIGURATION_MATCHED);
        assertThat(missingConfiguration(evaluate(plan, "order", List.of()))).isTrue();
    }
    @Test void mappingsAreBoundToTheActualRevisionClausesAndObjects() {
        assertThatThrownBy(() -> content(List.of(new ApplicationConstructionRequirement(Section.RULE, 1, "order", Mode.MANUAL, "", "核验")))).hasMessageContaining("引用本版");
        assertThatThrownBy(() -> content(List.of(new ApplicationConstructionRequirement(Section.RULE, 0, "other", Mode.MANUAL, "", "核验")))).hasMessageContaining("引用本版");
        assertThatThrownBy(() -> content(List.of(scope(), scope()))).hasMessageContaining("不能重复");
        assertThatThrownBy(() -> new ApplicationConstructionRequirement(Section.RELATION, 0, "order", Mode.FIELD, "customer", "文本冒充关联")).hasMessageContaining("不能兑现对象关联");
    }
}
