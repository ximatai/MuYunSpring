package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.ability.action.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformPresentationTemplateCatalogTest {
    private final PlatformPresentationTemplateCatalog catalog = new PlatformPresentationTemplateCatalog();

    @Test
    void shouldExposeOnlyTemplatesCompatibleWithTheClientAndPageContract() {
        assertThat(catalog.listFor(PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT))
                .extracting(PlatformPresentationTemplate::alias)
                .containsExactly("management");
        assertThat(catalog.listFor(PlatformPresentationClientType.MOBILE, PlatformPageContractType.MANAGEMENT))
                .isEmpty();
    }

    @Test
    void shouldValidateTheVersionedTemplateRootBeforePublication() {
        PlatformPresentationTemplate template = catalog.require("management", 1,
                PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT);
        PlatformPresentationRevision revision = revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":[]},
                  {"slot":"form","title":"详情","fields":[]}
                ]}
                """);

        catalog.validateUiTree(revision, template);

        revision.setUiTreeJson("{\"template\":\"management\",\"templateVersion\":2,\"nodes\":[]}");
        assertThatThrownBy(() -> catalog.validateUiTree(revision, template))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.presentation-revision.ui-tree-template-mismatch"));
    }

    @Test
    void shouldRejectTemplateUseOutsideItsDeclaredContract() {
        assertThatThrownBy(() -> catalog.require("management", 1,
                PlatformPresentationClientType.MOBILE, PlatformPageContractType.MANAGEMENT))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.presentation-template.client-unsupported"));
    }

    @Test
    void shouldValidateManagementComponentPropertySchemaAtPublicationBoundary() {
        PlatformPresentationTemplate template = catalog.require("management", 1,
                PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT);
        PlatformPresentationRevision revision = revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":[
                    {"field":"title","props":{"label":"名称","width":"160px","align":"left"}}
                  ]},
                  {"slot":"form","title":"详情","fields":[
                    {"field":"title","props":{"columnSpan":2,"readOnly":true}}
                  ]}
                ]}
                """);

        catalog.validateUiTree(revision, template);

        revision.setUiTreeJson("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":[{"field":"title","props":{"columnSpan":2}}]},
                  {"slot":"form","title":"详情","fields":["title"]}
                ]}
                """);
        assertThatThrownBy(() -> catalog.validateUiTree(revision, template))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.presentation-revision.ui-tree-management-invalid"));
    }

    private PlatformPresentationRevision revision(String uiTreeJson) {
        PlatformPresentationRevision revision = new PlatformPresentationRevision();
        revision.setTemplateAlias("management");
        revision.setTemplateVersion(1);
        revision.setUiTreeJson(uiTreeJson);
        return revision;
    }
}
