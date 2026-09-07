package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.ability.action.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformPresentationTemplateCatalogTest {
    private final PlatformPresentationTemplateCatalog catalog = new PlatformPresentationTemplateCatalog();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TREE_CARD", "LIST_CARD", "MICRO_LIST_CARD"})
    void validatesModeOwnedSlotsAndSearchBindings(String mode) {
        var template = catalog.require("management", 2, PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT);
        String navigation = mode.equals("LIST_CARD") ? "{\"slot\":\"list\",\"title\":\"记录列表\",\"fields\":[\"title\"]}"
                : "{\"slot\":\"explorer\",\"title\":\"导航\",\"fields\":[],\"titleField\":\"title\",\"secondaryField\":\"code\"}";
        String tree = "{\"template\":\"management\",\"templateVersion\":2,\"mode\":\"" + mode
                + "\",\"quickSearchFields\":[\"title\",\"code\"],\"nodes\":[" + navigation
                + ",{\"slot\":\"form\",\"title\":\"详情\",\"fields\":[\"title\"]}]}";
        catalog.validateUiTree(tree, template);
        assertThatThrownBy(() -> catalog.validateUiTree(tree.replace("[\"title\",\"code\"]", "[\"title\",\"title\"]"), template))
                .isInstanceOf(BusinessException.class);
        if (!mode.equals("LIST_CARD")) {
            assertThatThrownBy(() -> catalog.validateUiTree(tree.replace("\"slot\":\"explorer\"", "\"slot\":\"list\""), template))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> catalog.validateUiTree(tree.replace("\"titleField\":\"title\"", "\"titleField\":\"\""), template))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "[{\"field\":\"title\"}]",
            "[{\"field\":\"title\"},{\"field\":\"title\"}]",
            "[{\"group\":\"details\"},{\"field\":\"missing\"}]",
            "[{\"group\":\"details\"},{\"field\":\"title\",\"extra\":true}]",
            "null"
    })
    void rejectsIncompleteDuplicateAndUnknownFormOrder(String order) {
        var template = catalog.require("management", 1, PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT);
        var revision = revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":[]},
                  {"slot":"form","title":"表单","fields":["title"],
                   "groups":[{"group":"details","title":"分组","fields":[]}],"order":%s}]}
                """.formatted(order));
        assertThatThrownBy(() -> catalog.validateUiTree(revision, template)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsActionDeclarationsOnTheVersionTwoContract() throws Exception {
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"template":"management","templateVersion":2,"mode":"LIST_CARD","quickSearchFields":[],"actions":[],
                 "nodes":[{"slot":"list","title":"列表","fields":[]},{"slot":"form","title":"详情","fields":[]}]}
                """);
        assertThatThrownBy(() -> PlatformPresentationTemplateCatalog.validateModeAwareTree(root))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void validatesRelationColumnPropertiesAndRejectsUnsupportedWidthsAndFormProperties() {
        var template = catalog.require("management", 1, PlatformPresentationClientType.WEB,
                PlatformPageContractType.MANAGEMENT);
        String tree = """
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":[]},
                  {"slot":"form","title":"详情","fields":[],"relations":[
                    {"relation":"children","title":"子表","fields":["code",
                      {"field":"name","props":{"label":"姓名","width":"180px","align":"right"}}]}]}]}
                """;
        catalog.validateUiTree(revision(tree), template);
        for (String width : java.util.List.of("25%", "0px", "999999999999px", "bad")) {
            assertThatThrownBy(() -> catalog.validateUiTree(revision(tree.replace("180px", width)), template))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> catalog.validateUiTree(revision(tree.replace("\"align\":\"right\"", "\"readOnly\":true")), template))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.validateUiTree(revision(tree.replace("\"field\":\"name\"", "\"field\":\"code\"")), template))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldExposeOnlyTemplatesCompatibleWithTheClientAndPageContract() {
        assertThat(catalog.listFor(PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT))
                .extracting(PlatformPresentationTemplate::version)
                .containsExactly(1, 2, 3);
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
    void shouldAcceptOnlyFixedActionAnchorsInTheActionAwareTemplate() {
        PlatformPresentationTemplate template = catalog.require("management", 3,
                PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT);
        String tree = """
                {"template":"management","templateVersion":3,"mode":"LIST_CARD","quickSearchFields":[],
                 "actions":[{"actionCode":"approve","anchor":"detail"}],"nodes":[
                   {"slot":"list","title":"列表","fields":["title"]},
                   {"slot":"form","title":"详情","fields":["title"]}
                 ]}
                """;
        catalog.validateUiTree(tree, template);
        assertThatThrownBy(() -> catalog.validateUiTree(tree.replace("\"detail\"", "\"table-cell\""), template))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.validateUiTree(tree.replace("\"approve\"", "\"approve\",\"unexpected\":true"), template))
                .isInstanceOf(BusinessException.class);
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

    @Test
    void shouldAllowOnlyDeclaredDetailAssociationComponents() {
        PlatformPresentationTemplate template = catalog.require("management", 1,
                PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT);
        PlatformPresentationRevision revision = revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":[]},
                  {"slot":"form","title":"详情","fields":[],
                   "relations":[{"relation":"participants","title":"参考学生","fields":["studentName"]}]}
                ]}
                """);

        catalog.validateUiTree(revision, template);

        revision.setUiTreeJson("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":[],
                   "relations":[{"relation":"participants","title":"参考学生"}]},
                  {"slot":"form","title":"详情","fields":[]}
                ]}
                """);
        assertThatThrownBy(() -> catalog.validateUiTree(revision, template))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.presentation-revision.ui-tree-management-invalid"));
    }

    @Test
    void shouldAllowDistinctFormGroupsAndRejectAFieldPlacedTwice() {
        PlatformPresentationTemplate template = catalog.require("management", 1,
                PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT);
        PlatformPresentationRevision revision = revision("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":[]},
                  {"slot":"form","title":"详情","fields":[],"groups":[
                    {"group":"basic","title":"基础信息","fields":["examDate"]},
                    {"group":"subject","title":"科目信息","fields":["subject"]}
                  ]}
                ]}
                """);

        catalog.validateUiTree(revision, template);

        revision.setUiTreeJson("""
                {"template":"management","templateVersion":1,"nodes":[
                  {"slot":"list","title":"列表","fields":[]},
                  {"slot":"form","title":"详情","fields":[],"groups":[
                    {"group":"basic","title":"基础信息","fields":["examDate"]},
                    {"group":"subject","title":"科目信息","fields":["examDate"]}
                  ]}
                ]}
                """);
        assertThatThrownBy(() -> catalog.validateUiTree(revision, template))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.presentation-revision.ui-tree-management-invalid"));
    }

    @Test
    void shouldValidateOnlyTheDeclaredManagementListSearchPlaceholderRootProperty() {
        PlatformPresentationTemplate template = catalog.require("management", 1,
                PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT);
        PlatformPresentationRevision revision = revision("""
                {"template":"management","templateVersion":1,
                 "props":{"list":{"searchPlaceholder":"搜索考试名称"}},
                 "nodes":[
                   {"slot":"list","title":"列表","fields":[]},
                   {"slot":"form","title":"详情","fields":[]}
                 ]}
                """);

        catalog.validateUiTree(revision, template);

        revision.setUiTreeJson("""
                {"template":"management","templateVersion":1,"unexpected":true,"nodes":[
                  {"slot":"list","title":"列表","fields":[]},
                  {"slot":"form","title":"详情","fields":[]}
                ]}
                """);
        assertThatThrownBy(() -> catalog.validateUiTree(revision, template))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.presentation-revision.ui-tree-management-invalid"));

        revision.setUiTreeJson("""
                {"template":"management","templateVersion":1,
                 "props":{"form":{"searchPlaceholder":"不允许"}},
                 "nodes":[
                   {"slot":"list","title":"列表","fields":[]},
                   {"slot":"form","title":"详情","fields":[]}
                 ]}
                """);
        assertThatThrownBy(() -> catalog.validateUiTree(revision, template))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("platform.presentation-revision.ui-tree-management-invalid"));

        revision.setUiTreeJson("""
                {"template":"management","templateVersion":1,
                 "props":{"list":{"searchPlaceholder":" "}},
                 "nodes":[
                   {"slot":"list","title":"列表","fields":[]},
                   {"slot":"form","title":"详情","fields":[]}
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
