package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicCapabilityActionRuntimeAdapterTest {
    @Test
    void shouldDispatchTheSharedEnableDisableActionVectorToDynamicRuntime() {
        DynamicRecordService service = mock(DynamicRecordService.class);
        when(service.enableFromAction("sales", "contract", "record-1", "trace-1")).thenReturn(1);
        when(service.disableFromAction("sales", "contract", "record-1", "trace-1")).thenReturn(2);

        assertThat(DynamicCapabilityActionRuntimeAdapter.execute(owner(PlatformAction.ENABLE), PlatformAction.ENABLE,
                service, "sales", "contract", "record-1", "trace-1")).isEqualTo(1);
        assertThat(DynamicCapabilityActionRuntimeAdapter.execute(owner(PlatformAction.DISABLE), PlatformAction.DISABLE,
                service, "sales", "contract", "record-1", "trace-1")).isEqualTo(2);

        verify(service).enableFromAction("sales", "contract", "record-1", "trace-1");
        verify(service).disableFromAction("sales", "contract", "record-1", "trace-1");
    }

    @Test
    void shouldDispatchTheSharedSortActionVectorToDynamicRuntime() {
        DynamicRecordService service = mock(DynamicRecordService.class);

        assertThat(DynamicCapabilityActionRuntimeAdapter.execute(owner(PlatformAction.SORT), PlatformAction.SORT,
                service, "sales", "contract", DynamicActionExecutionRequest.empty()
                        .withRecordId("record-1").withBeforeId("record-2"), "trace-1")).isZero();

        verify(service).moveBeforeFromAction("sales", "contract", "record-1", "record-2", "trace-1");
    }

    @Test
    void shouldDispatchTheSameSortAfterVectorAndPreservePartitionRejection() {
        DynamicRecordService service = mock(DynamicRecordService.class);
        DynamicActionExecutionRequest after = DynamicActionExecutionRequest.empty()
                .withRecordId("moving").withAfterId("other-partition");
        doThrow(new net.ximatai.muyun.spring.common.exception.PlatformException(
                "Sort can only move records within the same partition: organizationId"))
                .when(service).moveAfterFromAction("sales", "contract", "moving", "other-partition", "trace-1");

        assertThatThrownBy(() -> DynamicCapabilityActionRuntimeAdapter.execute(owner(PlatformAction.SORT), PlatformAction.SORT,
                service, "sales", "contract", after, "trace-1"))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("same partition");
        verify(service).moveAfterFromAction("sales", "contract", "moving", "other-partition", "trace-1");
    }

    private net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution owner(PlatformAction action) {
        return CapabilityModuleRegistry.defaultRegistry().actionOwner(action).orElseThrow();
    }
}
