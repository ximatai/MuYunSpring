package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionResolver;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicPublishedPageDefinitionResolverTest {
    @Test
    void shouldCompileOnlyTheMainEntityChildAssociationWhenCodesOverlapAcrossEntities() {
        PlatformPageDefinitionService pageService = mock(PlatformPageDefinitionService.class);
        PlatformPresentationRevisionResolver revisionResolver = mock(PlatformPresentationRevisionResolver.class);
        DynamicPublishedPageDefinitionResolver resolver = new DynamicPublishedPageDefinitionResolver(
                pageService, revisionResolver);
        PlatformPageDefinition page = page();
        PlatformPresentationRevision revision = revision();
        DynamicModuleDescriptor module = new DynamicModuleDescriptor(
                "education.exam", "考试管理", "exam", List.of(),
                List.of(DynamicEntityDescriptor.from(new EntityDefinition("exam", "考试", "exam",
                        List.of(FieldDefinition.titleField()), Set.of()))),
                List.of(), List.of(), List.of(
                        new DynamicAssociationViewDescriptor("participants", "audit_log", "education.exam",
                                "audit_participant", AssociationViewDisplayMode.INLINE_LIST, "auditLog", null,
                                EntityViewType.LIST, true),
                        new DynamicAssociationViewDescriptor("participants", "exam", "education.exam",
                                "exam_participant", AssociationViewDisplayMode.INLINE_LIST, "exam", null,
                                EntityViewType.LIST, true),
                        new DynamicAssociationViewDescriptor("examLookup", "exam", "education.exam", "exam",
                                AssociationViewDisplayMode.INLINE_LIST, null, null, EntityViewType.LIST, true)));
        when(pageService.resolveGlobalPage("education.exam", DynamicPublishedPageDefinitionResolver.MANAGEMENT_PAGE_ALIAS))
                .thenReturn(Optional.of(page));
        when(revisionResolver.resolve("page-exam", PlatformPresentationClientType.WEB, null, null))
                .thenReturn(Optional.of(revision));

        ModuleUiDefinition definition = resolver.resolveWebGlobal(module).orElseThrow().definition();

        assertThat(definition.detailRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.code()).isEqualTo("participants");
            assertThat(relation.targetEntityAlias()).isEqualTo("exam_participant");
            assertThat(relation.parentBinding()).isEqualTo("exam");
        });
    }

    private PlatformPageDefinition page() {
        PlatformPageDefinition page = new PlatformPageDefinition();
        page.setId("page-exam");
        page.setModuleAlias("education.exam");
        page.setContractType(PlatformPageContractType.MANAGEMENT);
        return page;
    }

    private PlatformPresentationRevision revision() {
        PlatformPresentationRevision revision = new PlatformPresentationRevision();
        revision.setId("revision-exam");
        revision.setStatus(PlatformPresentationRevisionStatus.PUBLISHED);
        revision.setTemplateAlias(PlatformPresentationTemplateCatalog.MANAGEMENT_ALIAS);
        revision.setTemplateVersion(PlatformPresentationTemplateCatalog.MANAGEMENT_VERSION);
        revision.setUiTreeJson("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":["title"]},
                  {"slot":"form","title":"编辑考试","fields":["title"],
                   "relations":[{"relation":"participants","title":"参考学生","fields":[]}]}
                ]}
                """);
        return revision;
    }
}
