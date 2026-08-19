package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageCapabilityContractValidatorTest {
    @Test
    void shouldRejectTreeManagementWithoutTreeCapability() {
        assertThatThrownBy(() -> PageCapabilityContractValidator.validate("catalog.category", "TREE_MANAGEMENT",
                Set.of(), Set.of(), Set.of(PlatformAction.TREE.code())))
                .hasMessageContaining("consumer=TREE_MANAGEMENT")
                .hasMessageContaining("required=TREE");
    }

    @Test
    void shouldRejectEnabledStatusWithoutEnableOperations() {
        assertThatThrownBy(() -> PageCapabilityContractValidator.validate("catalog.category", "LIST_DETAIL_CARD",
                Set.of("ENABLED_STATUS"), Set.of(EntityCapability.ENABLE.name()),
                Set.of(PlatformAction.CREATE.code())))
                .hasMessageContaining("consumer=ENABLED_STATUS")
                .hasMessageContaining("required=enable");
    }

    @Test
    void shouldAcceptMatchingTemplateAndTraitContracts() {
        assertThatCode(() -> PageCapabilityContractValidator.validate("catalog.category", "TREE_MANAGEMENT",
                Set.of("STANDARD_CRUD", "ENABLED_STATUS", "RECYCLE_BIN"),
                Set.of(EntityCapability.TREE.name(), EntityCapability.ENABLE.name(), EntityCapability.RECYCLE_BIN.name()),
                Set.of(PlatformAction.TREE.code(), PlatformAction.CREATE.code(), PlatformAction.UPDATE.code(),
                        PlatformAction.DELETE.code(), PlatformAction.ENABLE.code(), PlatformAction.DISABLE.code(),
                        PlatformAction.RECYCLE_BIN_QUERY.code(), PlatformAction.RECYCLE_BIN_RESTORE.code())))
                .doesNotThrowAnyException();
    }
}
