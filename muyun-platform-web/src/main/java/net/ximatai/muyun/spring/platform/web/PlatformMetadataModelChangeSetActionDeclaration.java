package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import org.springframework.stereotype.Component;

/** Publishes the dedicated model-editor actions into the existing metadata relation governance module. */
@Component
@PlatformStaticActionDeclaration(module = ModuleMetadataRelationService.MODULE_ALIAS)
public class PlatformMetadataModelChangeSetActionDeclaration {
    @CustomActionEndpoint(value = "previewMetadataModelChangeSet", title = "预检数据模型变更集",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public void preview() {
        // Declaration-only: HTTP delivery is owned by PlatformMetadataModelChangeSetWebController.
    }

    @CustomActionEndpoint(value = "applyMetadataModelChangeSet", title = "发布数据模型变更集",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public void apply() {
        // Declaration-only: HTTP delivery is owned by PlatformMetadataModelChangeSetWebController.
    }
}
