package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldDefinitionCompiler;
import net.ximatai.muyun.spring.platform.ui.PageCompositionDraftCompiler;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final PlatformPresentationVariantService variantService;
    private final PlatformPresentationRevisionService revisionService;
    private final PlatformPageDefinitionService pageService;
    private final PlatformPresentationTemplateCatalog templateCatalog;
    private final PlatformModuleRuntimeContextService runtimeContextService;
    private final MetadataFieldDefinitionCompiler fields;

    public PresentationRevisionPreviewService(PlatformPresentationVariantService variantService,
                                              PlatformPresentationRevisionService revisionService,
                                              PlatformPageDefinitionService pageService,
                                              PlatformPresentationTemplateCatalog templateCatalog,
                                              PlatformModuleRuntimeContextService runtimeContextService,
                                              MetadataFieldDefinitionCompiler fields) {
        this.fields = fields;
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
        int previewVersion;
        try {
            var tree = OBJECT_MAPPER.readTree(request.uiTreeJson());
            previewVersion = tree == null ? -1 : tree.path("templateVersion").asInt(-1);
        } catch (JsonProcessingException exception) {
            throw BusinessExceptions.warning("platform.presentation-preview.ui-tree-invalid", "页面草稿必须为有效 JSON");
        }
        PlatformPresentationTemplate template = templateCatalog.require(revision.getTemplateAlias(),
                previewVersion, variant.getClientType(), page.getContractType());
        templateCatalog.validateUiTree(request.uiTreeJson(), template);

        var added = request.newFields();
        var childDrafts = PageCompositionDraftCompiler.childDrafts(request.uiTreeJson(), request.newChildren());
        var children = request.newChildren().stream().map(child -> new PlatformModuleRuntimeContextService.PreviewChild(
                PageCompositionDraftCompiler.childAlias(child.key()), child.title(),
                childDrafts.get(PageCompositionDraftCompiler.childAlias(child.key())).stream()
                    .map(draft -> fields.compile(draft.field())).toList())).toList();
        ResolvedModuleUiDescriptor descriptor = added.isEmpty() && children.isEmpty()
                ? runtimeContextService.previewDynamicPageDescriptor(page, revision, request.uiTreeJson())
                : runtimeContextService.previewDynamicPageDescriptor(page, revision, request.uiTreeJson(),
                    PageCompositionDraftCompiler.fieldDrafts(request.uiTreeJson(), added)
                        .stream().map(draft -> fields.compile(draft.field())).toList(), children);
        return new PresentationRevisionPreview(page.getId(), variant.getId(), revision.getId(), descriptor);
    }
}
