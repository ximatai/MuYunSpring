package net.ximatai.muyun.spring.platform.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultModuleRecordActionExecutorTest {
    @SuppressWarnings("unchecked") private final ObjectProvider<CrudAbility<?>> abilities = mock(ObjectProvider.class);
    private final DynamicRecordService records = mock(DynamicRecordService.class);
    private final DefaultModuleRecordActionExecutor executor = new DefaultModuleRecordActionExecutor(
            abilities, records, mock(ActionExecutionPolicyService.class), new ObjectMapper());

    @Test void staticDefaultOnlyHandlesUpdateAndLeavesDomainActionsToTheirExecutors() {
        var ability = mock(CrudAbility.class); when(ability.getModuleAlias()).thenReturn("sales.contract");
        when(abilities.orderedStream()).thenAnswer(call -> Stream.of(ability));
        assertThat(executor.supports("sales.contract", "update")).isTrue();
        assertThat(executor.supports("sales.contract", "deliver")).isFalse();
        verifyNoInteractions(records);
    }

    @Test void unknownModulesAndActionsAreNotClaimed() {
        when(abilities.orderedStream()).thenAnswer(call -> Stream.empty());
        when(records.moduleDefinitions()).thenReturn(List.of());
        assertThat(executor.supports("unknown", "update")).isFalse();
        assertThat(executor.supports("unknown", "deliver")).isFalse();
        verify(records, never()).actions(anyString());
        dynamicModule(); when(records.actions("dynamic.contract")).thenReturn(List.of());
        assertThat(executor.supports("dynamic.contract", "deliver")).isFalse();
    }

    @Test void dynamicDefaultUsesTheMainRecordActionDirectoryAndPreservesStandardUpdate() {
        dynamicModule();
        when(records.actions("dynamic.contract")).thenReturn(List.of(
                action("update", true, EntityActionLevel.RECORD, EntityActionExecutorType.STANDARD),
                action("deliver", true, EntityActionLevel.ANY, EntityActionExecutorType.SERVICE)));
        when(records.actionEntityAlias(eq("dynamic.contract"), anyString())).thenReturn("main");
        assertThat(executor.supports("dynamic.contract", "update")).isTrue();
        assertThat(executor.supports("dynamic.contract", "deliver")).isTrue();
        when(records.actionEntityAlias("dynamic.contract", "deliver")).thenReturn("child");
        assertThat(executor.supports("dynamic.contract", "deliver")).isFalse();
        for (var level : List.of(EntityActionLevel.LIST, EntityActionLevel.BATCH)) {
            when(records.actions("dynamic.contract")).thenReturn(List.of(action("deliver", true, level, EntityActionExecutorType.SERVICE)));
            assertThat(executor.supports("dynamic.contract", "deliver")).isFalse();
        }
        when(records.actions("dynamic.contract")).thenReturn(List.of(action("deliver", false, EntityActionLevel.RECORD, EntityActionExecutorType.SERVICE)));
        assertThat(executor.supports("dynamic.contract", "deliver")).isFalse();
        when(records.actions("dynamic.contract")).thenReturn(List.of(action("deliver", true, EntityActionLevel.RECORD, EntityActionExecutorType.WORKFLOW)));
        assertThat(executor.supports("dynamic.contract", "deliver")).isFalse();
    }

    private void dynamicModule() {
        when(abilities.orderedStream()).thenAnswer(call -> Stream.empty());
        var module = mock(ModuleDefinition.class); when(module.moduleAlias()).thenReturn("dynamic.contract");
        when(module.mainEntityAlias()).thenReturn("main");
        when(records.moduleDefinitions()).thenReturn(List.of(module));
    }
    private DynamicActionDescriptor action(String code, boolean enabled, EntityActionLevel level, EntityActionExecutorType type) {
        return new DynamicActionDescriptor(code, code, enabled, level, EntityActionCategory.CUSTOM,
                EntityActionAccessMode.AUTH_REQUIRED, true, true, null, false, null, type, "executor");
    }
}
