package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplate;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariant;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;
import org.springframework.stereotype.Service;

/**
 * Read-only compiler for an unsaved presentation tree. It deliberately does not use the
 * published-revision resolver: preview must not change runtime page selection or install a plan.
 */
@Service
public class PresentationRevisionPreviewService {
    private final PlatformPresentationVariantService variantService;
    private final PlatformPresentationRevisionService revisionService;
    private final PlatformPageDefinitionService pageService;
    private final PlatformPresentationTemplateCatalog templateCatalog;
    private final PlatformModuleRuntimeContextService runtimeContextService;

    public PresentationRevisionPreviewService(PlatformPresentationVariantService variantService,
                                              PlatformPresentationRevisionService revisionService,
                                              PlatformPageDefinitionService pageService,
                                              PlatformPresentationTemplateCatalog templateCatalog,
                                              PlatformModuleRuntimeContextService runtimeContextService) {
        this.variantService = variantService;
        this.revisionService = revisionService;
        this.pageService = pageService;
        this.templateCatalog = templateCatalog;
        this.runtimeContextService = runtimeContextService;
    }

    public PresentationRevisionPreview preview(String variantId, String revisionId,
                                               PresentationRevisionPreviewRequest request) {
        if (request == null || request.uiTreeJson() == null || request.uiTreeJson().isBlank()) {
            throw BusinessExceptions.warning("platform.presentation-preview.ui-tree-required",
                    "Presentation preview UI tree is required");
        }
        PlatformPresentationVariant variant = variantService.requireVisibleVariant(variantId);
        PlatformPresentationRevision revision = revisionService.requireVisibleRevision(variantId, revisionId);
        PlatformPageDefinition page = pageService.requireVisiblePage(variant.getPageId());
        PlatformPresentationTemplate template = templateCatalog.require(revision.getTemplateAlias(),
                revision.getTemplateVersion(), variant.getClientType(), page.getContractType());
        templateCatalog.validateUiTree(request.uiTreeJson(), template);

        ModuleUiDefinition definition = PageRevisionModuleUiDefinitionAdapter.fromPreviewRevision(page, revision,
                request.uiTreeJson(), runtimeContextService.dynamicMainFieldTitles(page.getModuleAlias()));
        ResolvedModuleUiDescriptor descriptor = runtimeContextService.previewDynamicPageDescriptor(page.getModuleAlias(),
                definition);
        return new PresentationRevisionPreview(page.getId(), variant.getId(), revision.getId(), descriptor);
    }
}
