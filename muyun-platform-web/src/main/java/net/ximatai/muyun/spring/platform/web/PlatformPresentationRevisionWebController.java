package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariant;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;
import net.ximatai.muyun.spring.web.NestedEnabledSortableCrudWebSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Objects;

/** Draft revisions are edited below a stable variant; publication is exposed as a separate action. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = PlatformPresentationRevisionService.MODULE_ALIAS, title = "平台页面呈现修订")
@RequestMapping("/platform.presentation-variant/{variantId}/revisions")
public class PlatformPresentationRevisionWebController
        extends NestedEnabledSortableCrudWebSupport<PlatformPresentationRevision, PlatformPresentationRevisionService> {
    private final PlatformPresentationVariantService variantService;
    private final PresentationRevisionPreviewService previewService;

    public PlatformPresentationRevisionWebController(PlatformPresentationVariantService variantService,
                                                     PresentationRevisionPreviewService previewService) {
        this.variantService = variantService;
        this.previewService = previewService;
    }

    @PostMapping("/{revisionId}/preview")
    @CustomActionEndpoint(value = "previewUiComposition", title = "预览页面呈现",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public PresentationRevisionPreview preview(@PathVariable String variantId,
                                               @PathVariable String revisionId,
                                               @RequestBody PresentationRevisionPreviewRequest request) {
        return previewService.preview(variantId, revisionId, request);
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        requireVariant(request);
        criteria.eq("variantId", pathVariable(request, "variantId"));
    }

    @Override
    protected void bindScope(PlatformPresentationRevision record, HttpServletRequest request) {
        requireVariant(request);
        record.setVariantId(pathVariable(request, "variantId"));
    }

    @Override
    protected boolean inScope(PlatformPresentationRevision record, HttpServletRequest request) {
        requireVariant(request);
        return Objects.equals(record.getVariantId(), pathVariable(request, "variantId"));
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "presentation revision does not belong to variant: " + pathVariable(request, "variantId") + "." + id;
    }

    private PlatformPresentationVariant requireVariant(HttpServletRequest request) {
        return variantService.requireVisibleVariant(pathVariable(request, "variantId"));
    }
}
