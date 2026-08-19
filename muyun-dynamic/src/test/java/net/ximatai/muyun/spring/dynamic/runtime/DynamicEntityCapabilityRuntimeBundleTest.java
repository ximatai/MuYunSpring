package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicEntityCapabilityRuntimeBundleTest {

    @Test
    void shouldDiagnoseMissingSortDependencyBeforeCreatingTreeRuntime() {
        EntityDefinition entity = mock(EntityDefinition.class);
        when(entity.supports(EntityCapability.TREE)).thenReturn(true);
        when(entity.supports(EntityCapability.CRUD)).thenReturn(true);
        when(entity.supports(EntityCapability.SORT)).thenReturn(false);
        when(entity.alias()).thenReturn("category");

        DynamicEntityCapabilityRuntimeBundle runtimes = DynamicEntityCapabilityRuntimeBundle.create(
                mock(DynamicEntityService.class), "catalog", entity, null);

        assertThatThrownBy(runtimes::tree)
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("TREE requires SORT capability: category");
    }
}
