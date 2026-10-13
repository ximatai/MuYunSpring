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
    void permissionCapabilityContributesAnIndependentlyAuthorizedRecordAction() {
        EntityDefinition entity = new EntityDefinition("contract", "contract", "Contract",
                List.of(FieldDefinition.string("code", "Code"))).withCapabilities(EntityCapability.DATA_SCOPE);
        assertThat(DynamicEntityDescriptor.from(entity).actions()).filteredOn(action -> action.code().equals("managePermissions"))
                .singleElement().satisfies(action -> {
                    assertThat(action.actionLevel()).isEqualTo(net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel.RECORD);
                    assertThat(action.dataAuth()).isTrue();
                    assertThat(action.actionAuth()).isTrue();
                });
    }

    @Test
    void shouldKeepRecycleBinLifecycleOutOfGenericDynamicActionDirectory() {
        EntityDefinition entity = new EntityDefinition("contract", "contract", "Contract",
                List.of(FieldDefinition.string("code", "Code")))
                .withCapabilities(EntityCapability.RECYCLE_BIN);

        assertThat(DynamicEntityDescriptor.from(entity).actions()).extracting(DynamicActionDescriptor::code)
                .doesNotContain(PlatformAction.RECYCLE_BIN_QUERY.code(), PlatformAction.RECYCLE_BIN_RESTORE.code(),
                        PlatformAction.RECYCLE_BIN_PURGE.code());
    }
    @Test
    void distinguishesGeneratedCodeTitlesFromOnlineOverridesEvenWhenTheirTextIsIdentical() {
        var entity = new EntityDefinition("contract", "contract", "Contract",
                List.of(FieldDefinition.string("code", "Code"))).withCapabilities(EntityCapability.CRUD);
        var generated = DynamicStandardActions.from("test.contract", entity, List.of()).stream()
                .filter(action -> action.code().equals(PlatformAction.UPDATE.code())).findFirst().orElseThrow();
        var override = net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition.enabled(
                "contract", PlatformAction.UPDATE.code(), PlatformAction.UPDATE.title());
        var configured = DynamicStandardActions.from("test.contract", entity, List.of(override)).stream()
                .filter(action -> action.code().equals(PlatformAction.UPDATE.code())).findFirst().orElseThrow();
        assertThat(generated.titleSource()).isEqualTo(net.ximatai.muyun.spring.common.platform.PlatformActionTextSources.declared(
                "test.contract", PlatformAction.UPDATE.code(), PlatformAction.UPDATE.title()));
        assertThat(configured.titleSource()).isEqualTo(net.ximatai.muyun.spring.common.platform.PlatformActionTextSources.configured(
                "test.contract", PlatformAction.UPDATE.code(), PlatformAction.UPDATE.title()));
        assertThat(generated.titleSource().originalText()).isEqualTo(configured.titleSource().originalText());
        assertThat(generated.titleSource().sourceKind()).isEqualTo(net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CODE);
        assertThat(configured.titleSource().sourceKind()).isEqualTo(net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CONFIG);
        assertThat(configured.withPermission("test.contract").titleSource()).isEqualTo(configured.titleSource());
        assertThat(configured.executorType()).isEqualTo(generated.executorType());
    }

}
