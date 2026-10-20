package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeBindingConfigurationGuardTest {
    @Test void referenceExclusionRunsUnderTheSharedLockForNewAndExistingDeclarations() {
        var references = mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService.class);
        var relations = mock(net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService.class);
        var metadata = mock(net.ximatai.muyun.spring.platform.metadata.MetadataService.class);
        var relation = new net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation();
        relation.setModuleAlias("demo.purchase"); relation.setMetadataId("schema");
        var schema = new net.ximatai.muyun.spring.platform.metadata.Metadata(); schema.setAlias("purchase");
        when(relations.select("main")).thenReturn(relation);
        when(metadata.select("schema")).thenReturn(schema);
        var guard = new CodeBindingConfigurationGuard(null, relations, metadata, references);
        var incoming = new ModuleMetadataField(); incoming.setMetadataFieldId("product"); incoming.setRelationId("main"); incoming.setBusinessCodeRole(CodeFieldRole.NORMAL);
        try (var locks = mockStatic(CodeConfigurationLocks.class)) {
            when(references.findForRelation("product", "main")).thenAnswer(call -> {
                locks.verify(() -> CodeConfigurationLocks.lock("demo.purchase", "purchase"));
                return new net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfig();
            });
            assertThatThrownBy(() -> guard.prepareValues(mock(CrudAbility.class), null, incoming)).hasMessageContaining("Reference fields");
            locks.clearInvocations();
            // Unchanged declarations must also check references under the same lock.
            assertThatThrownBy(() -> guard.prepareValues(mock(CrudAbility.class), incoming, incoming)).hasMessageContaining("Reference fields");
        }
    }
    @Test void standardMetadataSaveCannotMoveAnExistingCodeDeclarationToAnotherBusinessTarget() {
        var guard = new CodeBindingConfigurationGuard(null, null, null, org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService.class));
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
