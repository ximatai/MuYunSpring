package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultWorkflowRecordSummaryResolverTest {
    private final WorkflowActionPolicyService policy = mock(WorkflowActionPolicyService.class);
    private final ModuleRecordFacts facts = mock(ModuleRecordFacts.class);
    private final PlatformModuleService modules = mock(PlatformModuleService.class);
    private final DefaultWorkflowRecordSummaryResolver resolver = new DefaultWorkflowRecordSummaryResolver(policy, facts, modules);
    private WorkflowInstance record() {
        var instance = new WorkflowInstance(); instance.setModuleAlias("demo.purchase"); instance.setRecordId("r1");
        var module = new PlatformModule(); module.setTitle("采购申请");
        when(modules.resolveVisibleModule("demo.purchase")).thenReturn(module);
        return instance;
    }
    @Test void deniedBusinessNeverReadsItsTitle() {
        var instance = record(); doThrow(new PlatformAccessDeniedException("denied")).when(policy).requireRecordView(instance);
        assertThat(resolver.resolve(instance)).isEqualTo(new WorkflowRecordSummary(null, "采购申请", false));
        verifyNoInteractions(facts);
    }
    @Test void usesProtectedDisplayTitleRatherThanRawFacts() {
        var instance = record(); when(facts.displayTitle("demo.purchase", "r1")).thenReturn("***");
        assertThat(resolver.resolve(instance)).isEqualTo(new WorkflowRecordSummary("***", "采购申请", true));
        var order = inOrder(policy, facts); order.verify(policy).requireRecordView(instance);order.verify(facts).displayTitle("demo.purchase", "r1");
        verify(facts, never()).read(anyString(), anyString());
    }
    @Test void infrastructureFailureRemainsAnError() {
        var instance = record();when(facts.displayTitle("demo.purchase", "r1")).thenThrow(new PlatformException("database unavailable"));
        assertThatThrownBy(()->resolver.resolve(instance)).hasMessage("database unavailable");
    }
    @Test void thirdPartyFactsMustOptIntoProtectedPresentation() {
        ModuleRecordFacts custom = (module, id) -> java.util.Map.of("title", "secret");
        assertThat(custom.displayTitle("demo.purchase", "r1")).isNull();
    }
    @Test void catalogIncludesSharedAndCurrentTenantDefinitionsButExcludesOtherTenants() {
        var definitions = mock(WorkflowDefinitionService.class);
        var shared = new WorkflowDefinition(); shared.setModuleAlias("demo.shared");
        var own = new WorkflowDefinition(); own.setModuleAlias("demo.own"); own.setTenantId("tenant");
        var other = new WorkflowDefinition(); other.setModuleAlias("demo.other"); other.setTenantId("other");
        when(definitions.list(any(), any(), any(net.ximatai.muyun.database.core.orm.Sort[].class)))
                .thenReturn(java.util.List.of(shared, own, other));
        var visible = java.util.stream.Stream.of("demo.shared", "demo.own", "demo.other").map(alias -> {
            var module = new PlatformModule(); module.setAlias(alias); module.setTitle(alias); return module;
        }).toList();
        when(modules.listVisibleModules()).thenReturn(visible);
        try (var scope = net.ximatai.muyun.spring.common.tenant.TenantContext.use("tenant")) {
            assertThat(new DefaultWorkflowRecordSummaryResolver(policy, facts, modules, definitions).modules())
                    .containsOnlyKeys("demo.shared", "demo.own");
            assertThat(net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId()).contains("tenant");
        }
    }
}
