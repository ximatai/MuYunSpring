package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicRecycleBinCapabilityTest {
    @Test
    void shouldExposeRecycleBinOnlyForTheDeclaredDynamicEntityAndKeepEntityRecoveryIdentity() {
        DynamicRecordService service = mock(DynamicRecordService.class);
        when(service.entityDescriptor("sales.contract", "line")).thenReturn(descriptor(Set.of(
                EntityCapability.CRUD.name(), EntityCapability.RECYCLE_BIN.name())));
        DynamicEntityOperations operations = new DynamicEntityOperations(service, "sales.contract", "line");

        assertThat(operations).isInstanceOf(RecycleBinAbility.class);
        assertThat(operations.referenceTarget().qualifiedName()).isEqualTo("sales.contract.line");
        assertThat(operations.isRecycleBinPurgeEnabled()).isTrue();
        operations.beforeRecycleBinQuery();
        operations.beforeRecycleBinRestore();
    }

    @Test
    void shouldRejectRecycleBinLifecycleForADynamicEntityWithoutTheCapability() {
        DynamicRecordService service = mock(DynamicRecordService.class);
        when(service.entityDescriptor("sales.contract", "line")).thenReturn(descriptor(Set.of(EntityCapability.CRUD.name())));
        DynamicEntityOperations operations = new DynamicEntityOperations(service, "sales.contract", "line");

        assertThat(operations.isRecycleBinPurgeEnabled()).isFalse();
        assertThatThrownBy(operations::beforeRecycleBinQuery)
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not support capability: RECYCLE_BIN");
        assertThatThrownBy(() -> operations.beforeRecycleBinPurge("line-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not support capability: RECYCLE_BIN");
    }

    private DynamicEntityDescriptor descriptor(Set<String> capabilities) {
        return new DynamicEntityDescriptor("line", "Contract line", capabilities,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
