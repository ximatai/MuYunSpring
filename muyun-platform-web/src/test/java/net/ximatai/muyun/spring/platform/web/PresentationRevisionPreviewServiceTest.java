package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariant;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PresentationRevisionPreviewServiceTest {
    @Test
    void shouldCompileTransientDraftTreeThroughThePublishedDescriptorCompilerPath() {
        PlatformPresentationVariantService variantService = mock(PlatformPresentationVariantService.class);
        PlatformPresentationRevisionService revisionService = mock(PlatformPresentationRevisionService.class);
        PlatformPageDefinitionService pageService = mock(PlatformPageDefinitionService.class);
        PlatformModuleRuntimeContextService runtimeContexts = mock(PlatformModuleRuntimeContextService.class);
        PlatformPresentationVariant variant = variant();
        PlatformPresentationRevision revision = revision();
        PlatformPageDefinition page = page();
        ResolvedModuleUiDescriptor descriptor = mock(ResolvedModuleUiDescriptor.class);
        when(variantService.requireVisibleVariant("variant-1")).thenReturn(variant);
        when(revisionService.requireVisibleRevision("variant-1", "revision-2")).thenReturn(revision);
        when(pageService.requireVisiblePage("page-1")).thenReturn(page);
        when(runtimeContexts.dynamicMainFieldNames("education.exam")).thenReturn(List.of("title", "examDate"));
        when(runtimeContexts.previewDynamicPageDescriptor(any(), any())).thenReturn(descriptor);
        PresentationRevisionPreviewService service = new PresentationRevisionPreviewService(variantService, revisionService,
                pageService, new PlatformPresentationTemplateCatalog(), runtimeContexts);

        PresentationRevisionPreview result = service.preview("variant-1", "revision-2",
                new PresentationRevisionPreviewRequest("""
                        {"template":"management","templateVersion":1,"nodes":[
                          {"slot":"list","title":"考试列表","fields":["title"]},
                          {"slot":"form","title":"编辑考试","fields":["title","examDate"]}
                        ]}
                        """));

        assertThat(result.pageId()).isEqualTo("page-1");
        assertThat(result.variantId()).isEqualTo("variant-1");
        assertThat(result.revisionId()).isEqualTo("revision-2");
        assertThat(result.uiDescriptor()).isSameAs(descriptor);
        verify(runtimeContexts).previewDynamicPageDescriptor(eq("education.exam"), any(ModuleUiDefinition.class));
    }

    @Test
    void shouldRejectInvalidTransientTreeBeforeDynamicDescriptorCompilation() {
        PlatformPresentationVariantService variantService = mock(PlatformPresentationVariantService.class);
        PlatformPresentationRevisionService revisionService = mock(PlatformPresentationRevisionService.class);
        PlatformPageDefinitionService pageService = mock(PlatformPageDefinitionService.class);
        PlatformModuleRuntimeContextService runtimeContexts = mock(PlatformModuleRuntimeContextService.class);
        when(variantService.requireVisibleVariant("variant-1")).thenReturn(variant());
        when(revisionService.requireVisibleRevision("variant-1", "revision-2")).thenReturn(revision());
        when(pageService.requireVisiblePage("page-1")).thenReturn(page());
        PresentationRevisionPreviewService service = new PresentationRevisionPreviewService(variantService, revisionService,
                pageService, new PlatformPresentationTemplateCatalog(), runtimeContexts);

        assertThatThrownBy(() -> service.preview("variant-1", "revision-2",
                new PresentationRevisionPreviewRequest("{" + "\"template\":\"management\",\"templateVersion\":1,\"nodes\":[]}")))
                .hasMessageContaining("Management v1 UI tree");

        verifyNoMoreInteractions(runtimeContexts);
    }

    private PlatformPresentationVariant variant() {
        PlatformPresentationVariant variant = new PlatformPresentationVariant();
        variant.setId("variant-1");
        variant.setPageId("page-1");
        variant.setClientType(PlatformPresentationClientType.WEB);
        return variant;
    }

    private PlatformPresentationRevision revision() {
        PlatformPresentationRevision revision = new PlatformPresentationRevision();
        revision.setId("revision-2");
        revision.setVariantId("variant-1");
        revision.setStatus(PlatformPresentationRevisionStatus.DRAFT);
        revision.setTemplateAlias(PlatformPresentationTemplateCatalog.MANAGEMENT_ALIAS);
        revision.setTemplateVersion(PlatformPresentationTemplateCatalog.MANAGEMENT_VERSION);
        return revision;
    }

    private PlatformPageDefinition page() {
        PlatformPageDefinition page = new PlatformPageDefinition();
        page.setId("page-1");
        page.setModuleAlias("education.exam");
        page.setContractType(PlatformPageContractType.MANAGEMENT);
        return page;
    }
}
