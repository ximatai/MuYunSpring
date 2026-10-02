package net.ximatai.muyun.spring.platform.application;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ApplicationConstructionPlanBindingTest {
    private ApplicationConstructionPlanContent content(String target, String purpose) {
        return new ApplicationConstructionPlanContent("登记", "使用关联对象", List.of("引用目标"), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("source", "来源", "登记"),
                        new ApplicationConstructionPlanContent.BusinessObject("target", "目标", purpose, target)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("核对引用"),
                List.of(new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "source",
                        ApplicationConstructionRequirement.Mode.REFERENCE, "targetId", "引用目标", new ApplicationConstructionRequirement.Reference("target", ""))));
    }
    private ApplicationConstructionPlanService.Snapshot snapshot() {
        return new ApplicationConstructionPlanService.Snapshot("plan", 1, content(null, "登记"), Instant.EPOCH,
                "PARTIALLY_DELIVERED", List.of(
                    new ApplicationConstructionPlanService.Initialization("source", 1, "sample.source", "m1", "r1", "request1"),
                    new ApplicationConstructionPlanService.Initialization("target", 1, "sample.target", "m2", "r2", "request2")),
                List.of(), List.of(), List.of("source"));
    }
    @Test void preservesLegacyAssociationsButExplicitAssociationAndUnlinkTakePrecedence() {
        var legacy = snapshot();
        assertThat(legacy.moduleBindings()).extracting(ApplicationConstructionPlanService.ModuleBinding::moduleAlias)
                .containsExactly("sample.source", "sample.target");
        var linked = new ApplicationConstructionPlanService.Snapshot("plan", 2, content("sample.other", "登记"), Instant.EPOCH,
                "LINKED", legacy.initializations(), List.of(), List.of(), List.of());
        assertThat(linked.moduleBindings()).extracting(ApplicationConstructionPlanService.ModuleBinding::moduleAlias)
                .containsExactly("sample.source", "sample.other");
        var unlinked = new ApplicationConstructionPlanService.Snapshot("plan", 2, content("", "登记"), Instant.EPOCH,
                "LINKED", legacy.initializations(), List.of(), List.of(), List.of());
        assertThat(unlinked.moduleBindings()).extracting(ApplicationConstructionPlanService.ModuleBinding::moduleAlias)
                .containsExactly("sample.source");
    }
    @Test void partialDeliveryFreezesEffectiveReferenceMeaningButAllowsUnrelatedTargetChanges() {
        var legacy = snapshot();
        assertThatCode(() -> ApplicationConstructionPlanService.requireDeliveredContentUnchanged(legacy, content(null, "补充目标用途"))).doesNotThrowAnyException();
        assertThatCode(() -> ApplicationConstructionPlanService.requireDeliveredContentUnchanged(legacy, content("sample.target", "补充目标用途"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ApplicationConstructionPlanService.requireDeliveredContentUnchanged(legacy, content("sample.other", "登记"))).hasMessageContaining("已交付");
        assertThatThrownBy(() -> ApplicationConstructionPlanService.requireDeliveredContentUnchanged(legacy, content("", "登记"))).hasMessageContaining("已交付");
    }
}
