package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ModuleConfiguredTextSourceProviderTest {
    @Test
    void includesConfiguredActionSourcesWithoutAPageAndKeepsCodeDeclarationsOutOfOnlineMaintenance() {
        var modules = mock(PlatformModuleRuntimeContextService.class);
        when(modules.dynamicModuleAliases()).thenReturn(List.of("test.order"));
        var configured = ConfigurationTextSources.moduleActionTitle("test.order", "receive", "接收订单");
        var code = TextReference.code("action/test.order/receive/title", "接收订单");
        var action = new PlatformModuleRuntimeAction("receive", "receive", "接收订单",
                net.ximatai.muyun.spring.common.platform.PlatformActionLevel.RECORD, null,
                net.ximatai.muyun.spring.common.platform.ActionAccessMode.AUTH_REQUIRED, true, true,
                net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy.NONE, null, null, true, "allowed");
        when(modules.context("test.order")).thenReturn(new PlatformModuleRuntimeContext("test.order", "订单", ModuleKind.DYNAMIC,
                ModuleEntryType.MODULE, null, null, null, Set.of(), List.of(), Set.of(),
                List.of(action.withTitleSource(configured), action.withTitleSource(code)), null));
        var provider = new ModuleConfiguredTextSourceProvider(modules, new StaticModuleDefinitionCatalog(List.of()));
        assertThat(provider.currentSources("test.order")).containsExactly(configured);
        assertThat(provider.currentSources("missing.order")).isEmpty();
        verify(modules, never()).context("missing.order");
    }
}
