package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkflowConditionServiceTest {
    private final ModuleRecordFacts facts = mock(ModuleRecordFacts.class);
    private final WorkflowConditionService conditions = new WorkflowConditionService(facts);

    @Test
    void manualAdviceUsesOneCurrentBusinessSnapshotAndTreatsBlankAsMatched() {
        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("amount", 200));
        var expressions = new LinkedHashMap<String, String>();
        expressions.put("large", "{amount} >= 100");
        expressions.put("small", "{amount} < 100");
        expressions.put("empty", null);

        var matched = conditions.manualMatches(expressions, "sales.contract", "record-1");

        assertThat(matched).containsEntry("large", true).containsEntry("small", false).containsEntry("empty", true);
        verify(facts).read("sales.contract", "record-1");
    }

    @Test
    void blankConditionsNeedNoBusinessRead() {
        assertThat(conditions.matches(" ", Map.of())).isTrue();
        assertThat(conditions.manualMatches(Map.of("empty", " "), "sales.contract", "record-1"))
                .containsEntry("empty", true);
        verifyNoInteractions(facts);
    }

    @Test
    void malformedOrMutatingAdvisoryConditionsAreUnknownRatherThanMismatched() {
        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("amount", 200));
        var matched = conditions.manualMatches(Map.of("broken", "{amount} >", "mutating", "{amount} = 3"),
                "sales.contract", "record-1");
        assertThat(matched).containsEntry("broken", null).containsEntry("mutating", null);
        assertThatThrownBy(() -> conditions.matches("{amount} >", Map.of("amount", 200)))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void businessAccessFailureIsNotHiddenByAdvisoryFallback() {
        var denied = new PlatformException("record view denied");
        when(facts.read("sales.contract", "record-1")).thenThrow(denied);
        assertThatThrownBy(() -> conditions.manualMatches(Map.of("route", "{amount} > 100"),
                "sales.contract", "record-1")).isSameAs(denied);
    }

    @Test
    void automaticConditionsRequireBooleanResultsInsteadOfTextOrNumericTruthiness() {
        for (var expression : java.util.List.of("1", "0", "'true'", "'REJECTED'", "{missing}")) {
            assertThatThrownBy(() -> conditions.matches(expression, Map.of()))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("必须返回布尔值").hasMessageContaining("比较公式");
        }
        when(facts.read("sales.contract", "record-1")).thenReturn(Map.of("status", "REJECTED"));
        assertThatThrownBy(() -> conditions.matches("{status}", "sales.contract", "record-1"))
                .hasMessageContaining("必须返回布尔值");
        assertThat(conditions.matches("{status} == 'REJECTED'", "sales.contract", "record-1")).isTrue();
        assertThat(conditions.matches("false", Map.of())).isFalse();
        assertThat(conditions.matches("true", Map.of())).isTrue();
    }

    @Test
    void nonBooleanManualAdviceRemainsUnknownAndDoesNotBecomeAConditionHit() {
        var advice = conditions.manualMatches(Map.of("text", "{status}", "number", "1", "missing", "{missing}",
                "comparison", "{status} == 'REJECTED'"), Map.of("status", "REJECTED"));
        assertThat(advice).containsEntry("text", null).containsEntry("number", null)
                .containsEntry("missing", null).containsEntry("comparison", true);
    }
}
