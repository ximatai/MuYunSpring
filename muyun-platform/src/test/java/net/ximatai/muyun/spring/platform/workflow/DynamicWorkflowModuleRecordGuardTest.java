package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DynamicWorkflowModuleRecordGuardTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
    private final DynamicWorkflowModuleRecordGuard guard = new DynamicWorkflowModuleRecordGuard(dynamicRecordService);

    @Test
    void shouldRequireDynamicMainRecordSubmitScopeBeforeStartingWorkflow() {
        DynamicRecord record = record("record-1");
        when(dynamicRecordService.mainEntityAlias("sales.contract")).thenReturn("contract");
        when(dynamicRecordService.selectSystem("sales.contract", "contract", "record-1")).thenReturn(record);

        guard.beforeSubmit(WorkflowSubmitRequest.approval("sales.contract", "record-1"));

        verify(dynamicRecordService).requireRecordActionScope(eq("sales.contract"), eq("contract"), any(),
                eq(Set.of("record-1")), eq(Optional.empty()));
    }

    @Test
    void shouldRejectMissingDynamicRecordBeforeStartingWorkflow() {
        when(dynamicRecordService.mainEntityAlias("sales.contract")).thenReturn("contract");
        when(dynamicRecordService.selectSystem("sales.contract", "contract", "missing")).thenReturn(null);

        assertThatThrownBy(() -> guard.beforeSubmit(WorkflowSubmitRequest.approval("sales.contract", "missing")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("dynamic record not found");
    }

    @Test
    void shouldSkipModuleThatIsNotRegisteredAsDynamicModule() {
        when(dynamicRecordService.mainEntityAlias("static.contract"))
                .thenThrow(new ModuleDefinitionException("unknown module alias: static.contract"));

        guard.beforeSubmit(WorkflowSubmitRequest.approval("static.contract", "record-1"));

        verify(dynamicRecordService).mainEntityAlias("static.contract");
        verifyNoMoreInteractions(dynamicRecordService);
    }

    private DynamicRecord record(String id) {
        DynamicRecord record = new DynamicRecord(new EntityDefinition("contract", "app_contract", "Contract",
                List.of(FieldDefinition.string("code", "Code"))));
        record.setId(id);
        return record;
    }
}
