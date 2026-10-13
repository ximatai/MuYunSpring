package net.ximatai.muyun.spring.platform.module;

import org.junit.jupiter.api.Test;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import static org.assertj.core.api.Assertions.assertThat;

class ModuleTitleViewTest {
    @Test void identicalDirectoryTitlesKeepDeclarationAndConfigurationOwnershipSeparate() {
        var module = new PlatformModule(); module.setAlias("test.order"); module.setTitle("订单");
        module.setModuleKind(ModuleKind.STATIC); module.setSystemManaged(true);
        var declared = ModuleTitleView.of(module);
        module.setModuleKind(ModuleKind.DYNAMIC);
        var configured = ModuleTitleView.of(module);
        assertThat(declared.titleSource().sourceKind()).isEqualTo(TextReference.SourceKind.CODE);
        assertThat(configured.titleSource().sourceKind()).isEqualTo(TextReference.SourceKind.CONFIG);
        assertThat(declared.titleSource().sourceId()).isEqualTo("module/test.order/title");
        assertThat(configured.titleSource().sourceId()).isEqualTo("config/module/test.order/title");
        assertThat(declared.titleSource().sourceVersion()).isEqualTo(configured.titleSource().sourceVersion());
        module.setModuleKind(ModuleKind.STATIC); module.setSystemManaged(false);
        assertThat(module.titleSource().sourceKind()).isEqualTo(TextReference.SourceKind.CONFIG);
        module.setTitle(null);
        assertThat(ModuleTitleView.of(module).title()).isEqualTo("test.order");
        assertThat(module.titleSource()).isNull();
    }
}
