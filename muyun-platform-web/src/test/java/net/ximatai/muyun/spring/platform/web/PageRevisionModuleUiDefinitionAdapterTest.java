package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRevisionModuleUiDefinitionAdapterTest {
    @Test
    void shouldCompilePublishedManagementTreeToListDetailCardDefinition() {
        ModuleUiDefinition definition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试列表","fields":["title","examDate"]},
                  {"slot":"form","title":"编辑考试","fields":["title","subject","examDate"]}
                ]}
                """), List.of("title", "subject", "examDate"));

        assertThat(definition.moduleAlias()).isEqualTo("education.exam");
        assertThat(definition.page()).isInstanceOf(ListDetailCardPageDefinition.class);
        ListDetailCardPageDefinition page = (ListDetailCardPageDefinition) definition.page();
        assertThat(page.list().list().title()).isEqualTo("考试列表");
        assertThat(page.list().list().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("title", "examDate");
        assertThat(page.detail().editor().title()).isEqualTo("编辑考试");
        assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("title", "subject", "examDate");
        assertThat(page.list().searchPlaceholder()).isEqualTo("考试列表");
    }

    @Test
    void shouldCompileManagementListSearchPlaceholderForPublishedAndPreviewTrees() {
        String tree = """
                {"template":"management","templateVersion":1,
                 "props":{"list":{"searchPlaceholder":"搜索考试名称或日期"}},
                 "nodes":[
                   {"slot":"list","title":"考试列表","fields":["title"]},
                   {"slot":"form","title":"编辑考试","fields":["title"]}
                 ]}
                """;
        PlatformPresentationRevision published = revision(tree);
        PlatformPresentationRevision draft = revision(tree);
        draft.setStatus(PlatformPresentationRevisionStatus.DRAFT);

        ModuleUiDefinition publishedDefinition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(),
                published, List.of("title"));
        ModuleUiDefinition previewDefinition = PageRevisionModuleUiDefinitionAdapter.fromPreviewRevision(page(), draft,
                tree, List.of("title"));

        assertThat(listSearchPlaceholder(publishedDefinition)).isEqualTo("搜索考试名称或日期");
        assertThat(listSearchPlaceholder(previewDefinition)).isEqualTo("搜索考试名称或日期");
    }

    @Test
    void shouldRejectBlankDuplicateAndUnknownSlotFields() {
        assertThatThrownBy(() -> PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":["title","title"]},
                  {"slot":"form","title":"编辑考试","fields":["title"]}
                ]}
                """), List.of("title")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate fields");

        assertThatThrownBy(() -> PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":[""]},
                  {"slot":"form","title":"编辑考试","fields":["title"]}
                ]}
                """), List.of("title")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank field");

        assertThatThrownBy(() -> PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":["participant.name"]},
                  {"slot":"form","title":"编辑考试","fields":["title"]}
                ]}
                """), List.of("title")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown main entity field");
    }

    @Test
    void shouldCompilePersistedPageNodePropertiesWithoutChangingMetadataFields() {
        ModuleUiDefinition definition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":[
                    {"field":"title","props":{"label":"考试名称","width":"180px","align":"center"}}
                  ]},
                  {"slot":"form","title":"编辑考试","fields":[
                    {"field":"title","props":{"label":"名称","columnSpan":2,"readOnly":true}}
                  ]}
                ]}
                """), List.of("title"));

        ListDetailCardPageDefinition page = (ListDetailCardPageDefinition) definition.page();
        ViewFieldDefinition list = page.list().list().fields().getFirst();
        assertThat(list.label()).isEqualTo("考试名称");
        assertThat(list.width()).isEqualTo("180px");
        assertThat(list.align()).isEqualTo("center");
        ViewFieldDefinition form = page.detail().editor().fields().getFirst();
        assertThat(form.label()).isEqualTo("名称");
        assertThat(form.columnSpan()).isEqualTo(2);
        assertThat(form.readOnly().constant()).isTrue();
    }

    @Test
    void shouldRejectMissingRequiredSlotsAndUnpublishedRevisions() {
        assertThatThrownBy(() -> PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":["title"]}
                ]}
                """), List.of("title")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires a form slot");

        PlatformPresentationRevision draft = revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":["title"]},
                  {"slot":"form","title":"编辑考试","fields":["title"]}
                ]}
                """);
        draft.setStatus(PlatformPresentationRevisionStatus.DRAFT);
        assertThatThrownBy(() -> PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), draft,
                List.of("title")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be published");
    }

    @Test
    void shouldCompileTransientTreeForDraftWithoutChangingPublishedCompilationRule() {
        PlatformPresentationRevision draft = revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":["title"]},
                  {"slot":"form","title":"编辑考试","fields":["title"]}
                ]}
                """);
        draft.setStatus(PlatformPresentationRevisionStatus.DRAFT);

        ModuleUiDefinition definition = PageRevisionModuleUiDefinitionAdapter.fromPreviewRevision(page(), draft, """
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"草稿考试","fields":["title"]},
                  {"slot":"form","title":"草稿编辑","fields":["title"]}
                ]}
                """, List.of("title"));

        assertThat(((ListDetailCardPageDefinition) definition.page()).list().list().title()).isEqualTo("草稿考试");
        assertThat(draft.getUiTreeJson()).doesNotContain("草稿考试");
    }

    @Test
    void shouldUseDynamicMetadataTitleByDefaultAndPreserveExplicitTreeLabelForPublishedAndPreview() {
        String tree = """
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":["acceptanceNote",
                    {"field":"title","props":{"label":"页面标题"}}]},
                  {"slot":"form","title":"编辑考试","fields":["acceptanceNote","title"]}
                ]}
                """;
        Map<String, String> titles = Map.of("acceptanceNote", "验收说明（发布验证）", "title", "元数据标题");
        PlatformPresentationRevision published = revision(tree);
        PlatformPresentationRevision draft = revision(tree);
        draft.setStatus(PlatformPresentationRevisionStatus.DRAFT);

        ModuleUiDefinition publishedDefinition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(),
                published, titles);
        ModuleUiDefinition previewDefinition = PageRevisionModuleUiDefinitionAdapter.fromPreviewRevision(page(), draft,
                tree, titles);

        assertThat(labels(publishedDefinition)).containsExactly("验收说明（发布验证）", "页面标题",
                "验收说明（发布验证）", "元数据标题");
        assertThat(labels(previewDefinition)).containsExactlyElementsOf(labels(publishedDefinition));
    }

    private List<String> labels(ModuleUiDefinition definition) {
        ListDetailCardPageDefinition page = (ListDetailCardPageDefinition) definition.page();
        return java.util.stream.Stream.concat(page.list().list().fields().stream(), page.detail().editor().fields().stream())
                .map(ViewFieldDefinition::label).toList();
    }

    private String listSearchPlaceholder(ModuleUiDefinition definition) {
        return ((ListDetailCardPageDefinition) definition.page()).list().searchPlaceholder();
    }

    private PlatformPageDefinition page() {
        PlatformPageDefinition page = new PlatformPageDefinition();
        page.setId("page-exam");
        page.setModuleAlias("education.exam");
        page.setContractType(PlatformPageContractType.MANAGEMENT);
        return page;
    }

    private PlatformPresentationRevision revision(String uiTreeJson) {
        PlatformPresentationRevision revision = new PlatformPresentationRevision();
        revision.setId("revision-exam");
        revision.setStatus(PlatformPresentationRevisionStatus.PUBLISHED);
        revision.setTemplateAlias(PlatformPresentationTemplateCatalog.MANAGEMENT_ALIAS);
        revision.setTemplateVersion(PlatformPresentationTemplateCatalog.MANAGEMENT_VERSION);
        revision.setUiTreeJson(uiTreeJson);
        return revision;
    }
}
