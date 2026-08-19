package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    private net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution owner(PlatformAction action) {
        return CapabilityModuleRegistry.defaultRegistry().actionOwner(action).orElseThrow();
    }
}
