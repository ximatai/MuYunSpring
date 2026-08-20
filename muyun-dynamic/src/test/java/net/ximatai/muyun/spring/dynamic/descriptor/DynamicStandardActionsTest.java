package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicStandardActionsTest {
    @Test
    void shouldKeepRecycleBinLifecycleOutOfGenericDynamicActionDirectory() {
        EntityDefinition entity = new EntityDefinition("contract", "contract", "Contract",
                List.of(FieldDefinition.string("code", "Code")))
                .withCapabilities(EntityCapability.RECYCLE_BIN);

        assertThat(DynamicEntityDescriptor.from(entity).actions()).extracting(DynamicActionDescriptor::code)
                .doesNotContain(PlatformAction.RECYCLE_BIN_QUERY.code(), PlatformAction.RECYCLE_BIN_RESTORE.code(),
                        PlatformAction.RECYCLE_BIN_PURGE.code());
    }
}
