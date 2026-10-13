package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.workflow.*;
import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.web.WebPageResponse;
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
    @Test void projectsTheActualPaginatedWorkbenchResponseWithoutChangingPagingOrBusinessFacts() throws Exception {
        var card = mapper.convertValue(Map.of("instanceId", "instance-1", "moduleAlias", "sales.order",
                "recordId", "record-1", "workflowVersionId", "version-1", "nodeKey", "approval", "nodeTitle", "旧节点"), WorkflowWorkbenchCard.class);
        var source = ConfigurationTextSources.workflowNodeTitle("version-1", "approval", "旧节点");
        var projection = advice(Map.of(source, "Previous approval"));
        var method = java.util.Arrays.stream(net.ximatai.muyun.spring.platform.web.workflow.WorkflowRuntimeWebController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("workbenchPage")).findFirst().orElseThrow();
        assertThat(projection.supports(new org.springframework.core.MethodParameter(method, -1),
                org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class)).isTrue();
        var page = new WebPageResponse<>(List.of(card), 87, 3, 20, 5, true, Map.of("cursor", "unchanged"));
        try (var language = LanguageContext.use("en")) {
            var projected = mapper.valueToTree(projection.project(page));
            assertThat(projected.at("/records/0/nodeTitle").asText()).isEqualTo("Previous approval");
            assertThat(projected.at("/records/0/nodeTitleText/source/originalText").asText()).isEqualTo("旧节点");
            assertThat(projected.at("/records/0/recordId").asText()).isEqualTo("record-1");
            assertThat(projected.get("total").asLong()).isEqualTo(87);
            assertThat(projected.get("pageNum").asInt()).isEqualTo(3);
            assertThat(projected.at("/navigation/cursor").asText()).isEqualTo("unchanged");
        }
        assertThat(card.nodeTitle()).isEqualTo("旧节点");
    }
    @Test void projectsFrozenTaskGuideDisplaySlotsWithoutTouchingExecutableConfiguration() {
        var guide = new WorkflowTaskGuide(); guide.setGuideKey("read"); guide.setTitle("办理说明");
        guide.setGuideConfigText("{\"instruction\":\"请核对数据\",\"payload\":{\"title\":\"业务原值\"}}");
        var title = ConfigurationTextSources.workflowGuideText("v1", "task", "read", "title", "办理说明");
        var instruction = ConfigurationTextSources.workflowGuideText("v1", "task", "read", "instruction", "请核对数据");
        var process = new WorkflowModuleTaskProcessBundle("task-1", "instance-1", "v1", "task", "sales.order", "record-1", null, null, null,
                WorkflowModuleTaskEvaluation.manualConfirm(List.of(guide)), guide);
        try (var language = LanguageContext.use("en")) {
            var output = mapper.valueToTree(advice(Map.of(title, "Task instructions", instruction, "Check the data")).project(process));
            assertThat(output.at("/evaluation/guides/0/title").asText()).isEqualTo("Task instructions");
            assertThat(output.at("/evaluation/guides/0/instructionText/source/originalText").asText()).isEqualTo("请核对数据");
            assertThat(output.at("/evaluation/guides/0/instruction").asText()).isEqualTo("Check the data");
            assertThat(output.at("/evaluation/guides/0/guideConfigText").asText()).isEqualTo(guide.getGuideConfigText());
            assertThat(output.at("/nextGuide/titleText/source/sourceId").asText()).isEqualTo(title.sourceId());
            var wrapped = mapper.valueToTree(advice(Map.of(title, "Task instructions", instruction, "Check the data"))
                    .project(WorkflowModuleTaskContinueResult.blocked(process)));
            assertThat(wrapped.path("continued").asBoolean()).isFalse();
            assertThat(wrapped.at("/processBundle/evaluation/guides/0/titleText/source/sourceId").asText()).isEqualTo(title.sourceId());
            assertThat(wrapped.at("/processBundle/evaluation/guides/0/guideConfigText").asText()).isEqualTo(guide.getGuideConfigText());
            var method = java.util.Arrays.stream(net.ximatai.muyun.spring.platform.web.workflow.WorkflowRuntimeWebController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals("checkAndContinueModuleTask")).findFirst().orElseThrow();
            assertThat(advice(Map.of()).supports(new org.springframework.core.MethodParameter(method, -1),
                    org.springframework.http.converter.StringHttpMessageConverter.class)).isTrue();
        }
        assertThat(guide.getTitle()).isEqualTo("办理说明");
    }
    @Test void keepsAdministrativeNodeIdentityWhenOriginalTitlesRepeatAndDoesNotTranslateUserAddedNames() {
        var instance = mapper.convertValue(Map.of("instanceId", "i", "workflowVersionId", "v1", "activeNodeTitles", List.of("审批"), "activeNodeLabels", List.of(
                new WorkflowNodeTitleView("first", "审批", false), new WorkflowNodeTitleView("second", "审批", false), new WorkflowNodeTitleView("user", "审批", true))), WorkflowAdminInstanceView.class);
        var task = mapper.convertValue(Map.of("taskId", "t", "workflowVersionId", "v1", "nodeKey", "first", "nodeTitle", "审批"), WorkflowAdminActiveTaskView.class);
        var first = ConfigurationTextSources.workflowNodeTitle("v1", "first", "审批");
        var second = ConfigurationTextSources.workflowNodeTitle("v1", "second", "审批");
        try (var language = LanguageContext.use("en")) {
            var output = mapper.valueToTree(advice(Map.of(first, "First approval", second, "Second approval")).project(new WebListResponse<>(List.of(instance, task))));
            assertThat(output.at("/records/0/activeNodeLabels/0/title").asText()).isEqualTo("First approval");
            assertThat(output.at("/records/0/activeNodeLabels/1/title").asText()).isEqualTo("Second approval");
            assertThat(output.at("/records/0/activeNodeLabels/2/title").asText()).isEqualTo("审批");
            assertThat(output.at("/records/1/nodeTitleText/source/sourceId").asText()).isEqualTo(first.sourceId());
            assertThat(output.at("/records/1/taskId").asText()).isEqualTo("t");
        }
        assertThat(instance.activeNodeTitles()).containsExactly("审批");
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
