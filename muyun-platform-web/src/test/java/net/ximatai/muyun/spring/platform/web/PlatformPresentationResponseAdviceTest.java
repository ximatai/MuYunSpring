package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.workflow.*;
import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.web.WebTreeNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PlatformPresentationResponseAdviceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private PlatformPresentationResponseAdvice advice(Map<TextReference, String> english) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("languages", LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"));
        beans.registerSingleton("texts", (TextResolver) (source, arguments, tag) -> new TextResources.Resolution(
                "en".equals(tag) ? english.getOrDefault(source, source.originalText()) : source.originalText(), tag, tag, false, false));
        return new PlatformPresentationResponseAdvice(mapper, beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class));
    }
    @Test void localizesOnlyTypedNavigationAndOptionLabelsAndPreservesOriginalsAndCodes() {
        var menu = new Menu(); menu.setId("menu"); menu.setTitle("业务菜单"); menu.setModuleAlias("sales.order");
        var source = ConfigurationTextSources.menuTitle("menu", "业务菜单");
        var optionSource = TextReference.code("enum/test/ATTENDED/title", "已参加");
        var option = new OptionItem("ATTENDED", "已参加", true, 1, "ROOT", optionSource);
        var advice = advice(Map.of(source, "Business menu", optionSource, "Attended"));
        try (var language = LanguageContext.use("en")) {
            var tree = new WebListResponse<>(List.of(new WebTreeNode<>(MenuNavigationView.from(menu, null, null), List.of())));
            var navigation = mapper.valueToTree(advice.project(tree));
            assertThat(navigation.at("/records/0/record/title").asText()).isEqualTo("Business menu");
            assertThat(navigation.at("/records/0/record/titleText/source/originalText").asText()).isEqualTo("业务菜单");
            assertThat(navigation.at("/records/0/record/moduleAlias").asText()).isEqualTo("sales.order");
            var options = mapper.valueToTree(advice.project(List.of(option)));
            assertThat(options.at("/0/title").asText()).isEqualTo("Attended");
            assertThat(options.at("/0/code").asText()).isEqualTo("ATTENDED");
            assertThat(options.at("/0/parentCode").asText()).isEqualTo("ROOT");
            assertThat(advice.project(new WebListResponse<>(List.of(menu))).getClass()).isEqualTo(WebListResponse.class);
        }
        assertThat(menu.getTitle()).isEqualTo("业务菜单");
        assertThat(option.title()).isEqualTo("已参加");
    }
    @Test void resolvesFrozenWorkflowVersionRatherThanCurrentNodeNameAndLeavesUserAddedNodesUntouched() {
        var instance = new WorkflowInstance(); instance.setWorkflowVersionId("version-1");
        var original = new WorkflowNodeInstance(); original.setNodeKey("approval"); original.setNodeTitle("旧节点");
        var added = new WorkflowNodeInstance(); added.setNodeKey("added"); added.setNodeTitle("张三要求追加"); added.setAddedByAddSign(true);
        var frozen = ConfigurationTextSources.workflowNodeTitle("version-1", "approval", "旧节点");
        var current = ConfigurationTextSources.workflowNodeTitle("version-2", "approval", "新节点");
        var bundle = new WorkflowRuntimeRenderBundle("HISTORY", instance, List.of(original, added), List.of(), "冻结原文", "冻结布局");
        try (var language = LanguageContext.use("en")) {
            var projected = mapper.valueToTree(advice(Map.of(frozen, "Previous node", current, "Current node")).project(bundle));
            assertThat(projected.at("/nodes/0/nodeTitle").asText()).isEqualTo("Previous node");
            assertThat(projected.at("/nodes/0/nodeTitleText/source/sourceVersion").asText()).isEqualTo(TextReference.versionOf("旧节点"));
            assertThat(projected.at("/nodes/1/nodeTitle").asText()).isEqualTo("张三要求追加");
            assertThat(projected.get("semanticJson").asText()).isEqualTo("冻结原文");
            assertThat(projected.get("layoutJson").asText()).isEqualTo("冻结布局");
        }
        assertThat(original.getNodeTitle()).isEqualTo("旧节点");
    }
}
