package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StaticServiceAbilityCompilerTest {
    @Test
    void shouldCompileEnableAbilityThroughTheSameCapabilityModuleFacts() {
        PlatformModuleService service = mock(PlatformModuleService.class);

        assertThat(StaticServiceAbilityCompiler.compile(service)).contains(EntityCapability.ENABLE);
        assertThat(StaticServiceAbilityCompiler.standardActions(service))
                .contains(PlatformAction.ENABLE, PlatformAction.DISABLE);
    }
}
