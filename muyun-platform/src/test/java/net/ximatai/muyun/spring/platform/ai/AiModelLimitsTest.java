package net.ximatai.muyun.spring.platform.ai;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiModelLimitsTest {
    @Test
    void separatesDeclaredCapacityDefaultAndExplicitBudget() {
        var limits = new AiModelLimits(131072, 65536, 16384);
        assertThat(limits.outputBudget(null)).isEqualTo(16384);
        assertThat(limits.outputBudget(40000)).isEqualTo(40000);
        assertThat(AiModelLimits.UNKNOWN.outputBudget(null)).isEqualTo(8192);
        assertThat(new AiModelLimits(null, 2048, null).outputBudget(null)).isEqualTo(2048);
        assertThatThrownBy(() -> limits.outputBudget(65537)).hasMessageContaining("输出预算超过");
    }

    @Test
    void rejectsContradictoryAndNonPositiveDeclarations() {
        assertThatThrownBy(() -> new AiModelLimits(0, null, null)).hasMessageContaining("正整数");
        assertThatThrownBy(() -> new AiModelLimits(null, -1, null)).hasMessageContaining("正整数");
        assertThatThrownBy(() -> new AiModelLimits(null, null, 0)).hasMessageContaining("正整数");
        assertThatThrownBy(() -> new AiModelLimits(1024, 2048, null)).hasMessageContaining("不能超过");
        assertThatThrownBy(() -> new AiModelLimits(4096, 2048, 4096)).hasMessageContaining("默认输出预算");
        assertThatThrownBy(() -> new AiModelLimits(4096, null, null)).hasMessageContaining("默认输出预算");
        assertThatThrownBy(() -> new AiModelLimits(4096, null, 4096)).hasMessageContaining("默认输出预算");
    }
}
