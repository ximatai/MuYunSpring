package net.ximatai.muyun.spring.platform.task;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ModuleCompletionCheckServiceTest {
    private final Map<String, Object> values = Map.of("status", "REJECTED", "amount", 200, "delivered", true);
    private final ModuleRecordFacts facts = (module, record) -> values;
    private final ModuleCompletionCheckService checks = new ModuleCompletionCheckService(
            mock(DynamicRecordService.class), mock(PlatformQueryItemService.class),
            mock(PlatformQueryTemplateService.class), Optional.empty(), mock(ObjectProvider.class), facts);

    @Test
    void completionGateRequiresBooleanRatherThanTruthyBusinessValues() {
        for (String expression : new String[]{"{status}", "1", "'true'"}) {
            assertThatThrownBy(() -> checks.formula("demo.purchase", "r1", expression))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("必须返回布尔值");
        }
        assertThat(checks.formula("demo.purchase", "r1", "{status} == 'APPROVED'")).isFalse();
        assertThat(checks.formula("demo.purchase", "r1", "{delivered} && {amount} >= 100")).isTrue();
        assertThat(checks.formula("demo.purchase", "r1", "false")).isFalse();
    }

    @Test
    void completionGateRejectsAssignmentAndBlankExpressions() {
        assertThatThrownBy(() -> checks.formula("demo.purchase", "r1", "{amount} = 1"))
                .isInstanceOf(PlatformException.class).hasMessageContaining("不能修改业务字段");
        assertThatThrownBy(() -> checks.formula("demo.purchase", "r1", " "))
                .isInstanceOf(PlatformException.class).hasMessageContaining("不能为空");
        assertThat(values).containsEntry("amount", 200);
    }
}
