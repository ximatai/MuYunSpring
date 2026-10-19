package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeBindingConfigurationGuardTest {
    @Test void standardMetadataSaveCannotMoveAnExistingCodeDeclarationToAnotherBusinessTarget() {
        var guard = new CodeBindingConfigurationGuard(null, null, null);
        var before = new ModuleMetadataField(); before.setBusinessCodeRole(CodeFieldRole.NORMAL);
        before.setRelationId("source"); before.setMetadataFieldId("number");
        var incoming = new ModuleMetadataField(); incoming.setBusinessCodeRole(CodeFieldRole.NORMAL);
        incoming.setRelationId("target"); incoming.setMetadataFieldId("number");
        assertThatThrownBy(() -> guard.prepareValues(mock(CrudAbility.class), before, incoming))
                .hasMessageContaining("cannot be moved");
        incoming.setRelationId("source"); incoming.setMetadataFieldId("other");
        assertThatThrownBy(() -> guard.prepareValues(mock(CrudAbility.class), before, incoming))
                .hasMessageContaining("cannot be moved");
    }
}
