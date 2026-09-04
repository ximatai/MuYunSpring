package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetApplyCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetApplyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetPreview;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetPreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetPreviewService;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetPublishResult;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/** Dedicated web projection for the one visible metadata model tree draft. */
@RestController
@PlatformStaticWebProjection(module = ModuleMetadataRelationService.MODULE_ALIAS)
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/platform.module/{moduleAlias}/metadata-model")
public class PlatformMetadataModelChangeSetWebController extends WebSupport<MetadataModelChangeSetApplyService>
        implements SystemScope<MetadataModelChangeSetApplyService> {
    private final MetadataModelChangeSetPreviewService previewService;

    public PlatformMetadataModelChangeSetWebController(MetadataModelChangeSetPreviewService previewService,
                                                        MetadataModelChangeSetApplyService applyService) {
        this.previewService = previewService;
        this.service = applyService;
    }

    @PostMapping("/change-set-preview")
    @CustomActionEndpoint(value = "previewMetadataModelChangeSet", title = "预检数据模型变更集",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public MetadataModelChangeSetPreview preview(HttpServletRequest request,
                                                 @RequestBody MetadataModelChangeSetPreviewCommand command) {
        return webScope(() -> previewService.preview(moduleAlias(request), command));
    }

    @PostMapping("/change-set-apply")
    @CustomActionEndpoint(value = "applyMetadataModelChangeSet", title = "发布数据模型变更集",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public MetadataModelChangeSetPublishResult apply(HttpServletRequest request,
                                                     @RequestBody MetadataModelChangeSetApplyCommand command) {
        return webScope(() -> service().apply(moduleAlias(request), command));
    }

    @SuppressWarnings("unchecked")
    private String moduleAlias(HttpServletRequest request) {
        Object attributes = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String value = attributes instanceof Map<?, ?> values ? (String) values.get("moduleAlias") : null;
        return PlatformNameRules.requireModuleAlias(value);
    }
}
