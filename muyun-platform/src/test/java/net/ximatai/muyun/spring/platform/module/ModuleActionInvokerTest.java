package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.ability.event.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleActionInvokerTest {
    private final PlatformModuleService modules = mock(PlatformModuleService.class);
    private final PlatformModuleActionService actions = mock(PlatformModuleActionService.class);
    private final DynamicRecordService records = mock(DynamicRecordService.class);
    private final ActionExecutionPolicyService policies = mock(ActionExecutionPolicyService.class);
    private final List<RuntimeEvent> events = new ArrayList<>();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private ModuleActionInvoker invoker;
    private PlatformModule module;
    private PlatformModuleAction action;
    @BeforeEach void setup() {
        module = new PlatformModule(); module.setAlias("test.target"); module.setModuleKind(ModuleKind.DYNAMIC); module.setEnabled(true);
        action = new PlatformModuleAction(); action.setModuleAlias("test.target"); action.setActionCode("execute");
        action.setActionLevel(EntityActionLevel.LIST); action.setExecutorType(EntityActionExecutorType.SERVICE);
        action.setExecutorKey("fixture"); action.setEnabled(true); action.setSchedulable(true);
        when(modules.select("test.target")).thenReturn(module);
        when(actions.findByModuleAliasAndActionCode("test.target", "execute")).thenReturn(action);
        when(actions.requireExecutionPolicy("test.target", "execute")).thenAnswer(call -> action.executionPolicy());
        when(policies.authorize(any())).thenAnswer(call -> ActionAuthorizationResult.allowed(call.getArgument(0), "ROLE_GRANTED"));
        beans.registerSingleton("records", records);
        invoker = new ModuleActionInvoker(modules, actions, policies, beans.getBeanProvider(DynamicRecordService.class),
                beans.getBeanProvider(ScheduledModuleAction.class), events::add);
    }
    @Test void dynamicRequiresExplicitDeclarationAndHeadlessModuleLevel() {
        action.setSchedulable(false); rejected(); action.setSchedulable(true);
        for (var level : List.of(EntityActionLevel.RECORD, EntityActionLevel.BATCH, EntityActionLevel.ANY)) { action.setActionLevel(level); rejected(); }
        action.setActionLevel(EntityActionLevel.LIST); action.setFormSupported(true); rejected(); action.setFormSupported(false);
        action.setExecutorType(EntityActionExecutorType.DIALOG); rejected(); action.setExecutorType(EntityActionExecutorType.SERVICE);
        invoker.invoke("test.target", "execute", Map.of("revision", 1));
        verify(records).executeAction(eq("test.target"), eq("execute"), argThat(request -> request.payload().equals(Map.of("revision", 1))));
        assertThat(events).isEmpty();
    }
    @Test void everyInvocationRereadsCurrentMetadataAndTenantOwnership() {
        invoker.validateTarget("test.target", "execute");
        action.setEnabled(false); rejected(); action.setEnabled(true);
        module.setEnabled(false); rejected(); module.setEnabled(true);
        try (var tenant = TenantContext.use("tenant-a")) { module.setTenantId("tenant-b"); rejected(); }
        module.setTenantId(null); when(actions.findByModuleAliasAndActionCode("test.target", "execute")).thenReturn(null); rejected();
    }
    @Test void staticContributionKeepsAuthorizedContextAndAuditsSuccessAndFailure() {
        module.setModuleKind(ModuleKind.STATIC); action.setSchedulable(false); rejected();
        var failure = new java.util.concurrent.atomic.AtomicBoolean();
        beans.registerSingleton("static", new ScheduledModuleAction() {
            public String moduleAlias() { return "test.target"; }
            public String actionCode() { return "execute"; }
            public Object execute(Map<String, Object> values) {
                assertThat(ActionExecutionContextHolder.current().orElseThrow().authorizationResult().decision()).isEqualTo("ROLE_GRANTED");
                if (failure.get()) throw new IllegalStateException("private business data"); return "ok";
            }
        });
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("user", "user", "tenant-a")); var tenant = TenantContext.use("tenant-a");
             var job = JobExecutionContext.use(new JobExecutionContext.Reference("test.job", "plan", "run", "INITIATOR"))) {
            assertThat(invoker.invoke("test.target", "execute", Map.of())).isEqualTo("ok");
            failure.set(true); assertThatThrownBy(() -> invoker.invoke("test.target", "execute", Map.of())).hasMessage("private business data");
        }
        assertThat(events).extracting(RuntimeEvent::eventType).containsExactly(RuntimeEventType.ACTION_EXECUTED, RuntimeEventType.ACTION_FAILED);
        assertThat(events.getFirst().authorizationDecision()).isEqualTo("ROLE_GRANTED");
        assertThat(events.getFirst().auditContext()).containsValue("INITIATOR");
        assertThat(events.getLast().payload()).isEmpty();
        assertThat(ActionExecutionContextHolder.current()).isEmpty();
        verifyNoInteractions(records);
    }
    @Test void deniedStaticActionCannotRunDomainParameterValidation() {
        module.setModuleKind(ModuleKind.STATIC);
        var contribution = mock(ScheduledModuleAction.class);
        when(contribution.moduleAlias()).thenReturn("test.target"); when(contribution.actionCode()).thenReturn("execute");
        beans.registerSingleton("static", contribution);
        doThrow(new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("denied")).when(policies).authorize(any());
        assertThatThrownBy(() -> invoker.requireAuthorized("test.target", "execute", Map.of())).hasMessage("denied");
        assertThatThrownBy(() -> invoker.invoke("test.target", "execute", Map.of())).hasMessage("denied");
        verify(contribution, never()).validate(anyMap()); verify(contribution, never()).execute(anyMap());
    }
    private void rejected() { assertThatThrownBy(() -> invoker.validateTarget("test.target", "execute")).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class); }
}
