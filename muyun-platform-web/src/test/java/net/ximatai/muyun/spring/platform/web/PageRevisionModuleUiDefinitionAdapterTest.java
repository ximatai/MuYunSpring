package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;
import net.ximatai.muyun.spring.platform.module.DynamicModuleOverviewMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRevisionModuleUiDefinitionAdapterTest {
    @Test
    void compilesInterleavedFieldsAndGroupsInDeclaredOrder() {
        var tree = """
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":["title"]},
                  {"slot":"form","title":"表单","fields":["title","code"],
                   "groups":[{"group":"details","title":"分组","fields":["note"]},
                             {"group":"empty","title":"空组","fields":[]}],
                   "order":[{"field":"title"},{"group":"empty"},{"group":"details"},{"field":"code"}]}]}
                """;
        var definition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision(tree),
                List.of("title", "code", "note"));
        var editor = ModuleUiDescriptorCompiler.compile(definition).page().detail().editor();
        assertThat(editor.fields()).extracting(field -> field.fieldRef().fieldName()).containsExactly("title", "note", "code");
        assertThat(editor.formGroups()).extracting(ResolvedFormGroupDescriptor::groupCode).containsExactly("details", "empty");
        assertThat(editor.formGroups().getFirst().fields()).extracting(ViewFieldRef::fieldName).containsExactly("note");
    }

    @Test
    void shouldCompileVersionThreeActionPlacementsIntoTheResolvedPage() {
        var revision = revision("""
                {"template":"management","templateVersion":3,"mode":"LIST_CARD","quickSearchFields":[],
                 "actions":[{"actionCode":"create","anchor":"page"},{"actionCode":"update","anchor":"detail"},{"actionCode":"delete","anchor":"form"}],
                 "nodes":[{"slot":"list","title":"列表","fields":["title"]},{"slot":"form","title":"详情","fields":["title"]}]}
                """);
        revision.setTemplateVersion(3);
        ModuleUiDefinition definition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision,
                new DynamicPageCompilationContext(DynamicModuleOverviewMode.LIST_CARD,
                        Map.of("title", "名称"), java.util.Set.of(), Map.of()));

        assertThat(definition.pageActions()).containsExactly(
                new PageActionDefinition("create", PageActionAnchor.PAGE),
                new PageActionDefinition("update", PageActionAnchor.DETAIL),
                new PageActionDefinition("delete", PageActionAnchor.FORM));
        assertThat(ModuleUiDescriptorCompiler.compile(definition).page().actions()).containsExactly(
                new ResolvedPageActionDescriptor("create", PageActionAnchor.PAGE),
                new ResolvedPageActionDescriptor("update", PageActionAnchor.DETAIL),
                new ResolvedPageActionDescriptor("delete", PageActionAnchor.FORM));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TREE_CARD", "MICRO_LIST_CARD"})
    void pinsNavigationBindingsAndSearchIndependentlyOfTheModuleMode(String mode) {
        var revision = revision("""
                {"template":"management","templateVersion":2,"mode":"%s","quickSearchFields":["code"],"nodes":[
                  {"slot":"explorer","title":"导航","fields":[],"titleField":"code","secondaryField":"title"},
                  {"slot":"form","title":"详情","fields":["title"]}
                ]}
                """.formatted(mode));
        revision.setTemplateVersion(2);
        var definition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision,
                new DynamicPageCompilationContext(DynamicModuleOverviewMode.LIST_CARD,
                        Map.of("title", "名称", "code", "编码"), java.util.Set.of(), Map.of()));
        assertThat(definition.page().quickSearchFields()).containsExactly("code");
        PageExplorerDefinition explorer = definition.page() instanceof TreeManagementPageDefinition tree
                ? tree.explorer() : ((FlatManagementPageDefinition) definition.page()).explorer();
        assertThat(explorer.titleField()).isEqualTo("code");
        assertThat(explorer.secondaryField()).isEqualTo("title");
        assertThat(definition.page().template()).isEqualTo(mode.equals("TREE_CARD")
                ? ModulePageTemplate.TREE_MANAGEMENT : ModulePageTemplate.FLAT_MANAGEMENT);
        assertThatThrownBy(() -> PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision,
                new DynamicPageCompilationContext(DynamicModuleOverviewMode.LIST_CARD,
                        Map.of("title", "名称"), java.util.Set.of(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCompileEachDynamicOverviewModeToItsPageTemplate() {
        PlatformPresentationRevision revision = revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试列表","fields":["title"]},
                  {"slot":"form","title":"编辑考试","fields":["title"]}
                ]}
                """);

        ModuleUiDefinition tree = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision,
                new DynamicPageCompilationContext(DynamicModuleOverviewMode.TREE_CARD,
                        Map.of("title", "考试名称"), java.util.Set.of(), Map.of()));
        ModuleUiDefinition list = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision,
                new DynamicPageCompilationContext(DynamicModuleOverviewMode.LIST_CARD,
                        Map.of("title", "考试名称"), java.util.Set.of(), Map.of()));
        ModuleUiDefinition micro = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision,
                new DynamicPageCompilationContext(DynamicModuleOverviewMode.MICRO_LIST_CARD,
                        Map.of("title", "考试名称"), java.util.Set.of(), Map.of()));

        assertThat(tree.page()).isInstanceOf(TreeManagementPageDefinition.class);
        assertThat(list.page()).isInstanceOf(ListDetailCardPageDefinition.class);
        assertThat(micro.page()).isInstanceOf(FlatManagementPageDefinition.class);
        assertThat(((FlatManagementPageDefinition) micro.page()).explorer().title()).isEqualTo("考试列表");
    }

    @Test
    void shouldRetainDynamicRequiredFieldFactInThePublishedForm() {
        ModuleUiDefinition definition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试列表","fields":["title"]},
                  {"slot":"form","title":"编辑考试","fields":["title"]}
                ]}
                """), new DynamicPageCompilationContext(DynamicModuleOverviewMode.LIST_CARD,
                Map.of("title", "考试名称"), java.util.Set.of("title"), Map.of()));

        ViewFieldDefinition field = ((ListDetailCardPageDefinition) definition.page()).detail().editor().fields().getFirst();
        assertThat(field.required().constant()).isTrue();
    }

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
    void shouldCompileDeclaredDetailAssociationFromTheDynamicRuntimeCatalog() {
        ModuleUiDefinition definition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page(), revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"考试","fields":["title"]},
                  {"slot":"form","title":"编辑考试","fields":["title"],
                   "relations":[{"relation":"participants","title":"参考学生","fields":[{"field":"studentName","props":{"label":"学生姓名","width":"180px","align":"right"}}]}]}
                ]}
                """), Map.of("title", "考试名称"), Map.of("participants",
                new DynamicAssociationViewDescriptor("participants", "exam", "education.exam", "exam_participant",
                        AssociationViewDisplayMode.INLINE_LIST, "participants", null, EntityViewType.LIST, true)));

        assertThat(definition.detailRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.code()).isEqualTo("participants");
            assertThat(relation.title()).isEqualTo("参考学生");
            assertThat(relation.targetEntityAlias()).isEqualTo("exam_participant");
            assertThat(relation.listFields()).containsExactly("studentName");
            var resolved = relation.applyColumnProperties(new net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationListField(
                    "studentName", "姓名", null, "text", "STRING", 100, "left", 2));
            assertThat(resolved.title()).isEqualTo("学生姓名");
            assertThat(resolved.width()).isEqualTo(180);
            assertThat(resolved.align()).isEqualTo("right");
            assertThat(resolved.fieldUiControlAlias()).isEqualTo("text");
            assertThat(resolved.maxDisplayLines()).isEqualTo(2);
        });
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
