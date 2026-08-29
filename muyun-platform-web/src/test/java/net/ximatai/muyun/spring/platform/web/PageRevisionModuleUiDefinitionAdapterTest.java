package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

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
