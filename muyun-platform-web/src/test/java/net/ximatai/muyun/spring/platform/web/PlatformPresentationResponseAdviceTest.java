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
        return adviceForTranslations(Map.of("en", english));
    }
    private PlatformPresentationResponseAdvice adviceForTranslations(Map<String, Map<TextReference, String>> translations) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("languages", LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"));
        beans.registerSingleton("texts", (TextResolver) (source, arguments, tag) -> new TextResources.Resolution(
                translations.getOrDefault(tag, Map.of()).getOrDefault(source, source.originalText()), tag, tag, false, false));
        return new PlatformPresentationResponseAdvice(mapper, beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class));
    }
    @org.springframework.web.bind.annotation.RestController
    static class CatalogFixture {
        @org.springframework.web.bind.annotation.GetMapping("/catalog-fixture")
        public List<OpenApiModuleCatalogItem> catalog() {
            return List.of(new OpenApiModuleCatalogItem("test.module", "模块", "static", "/test.module/openapi",
                    TextReference.code("test.title", "模块")));
        }
    }

    @Test void serializesCatalogProjectionUsingTheTypedControllerReturnSignature() throws Exception {
        var source = TextReference.code("test.title", "模块");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CatalogFixture())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(advice(Map.of(source, "Module"))).build();
        try (var language = LanguageContext.use("en")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/catalog-fixture"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].title").value("Module"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].titleText.source.originalText").value("模块"));
        }
    }

    @Test void projectsDeclaredAvailabilityReasonsWithoutChangingDecisionsOrLiteralBusinessReasons() {
        var code = TextReference.code("platform.managed-application.edit-unavailable", "平台托管应用不可编辑");
        var config = TextReference.configured("config/action/reason", "配置的限制");
        var input = new PlatformRecordActionAvailability("record", List.of(
                new PlatformRecordActionAvailability.Action("update", false, code.originalText(), code),
                new PlatformRecordActionAvailability.Action("submit", false, config.originalText(), config),
                new PlatformRecordActionAvailability.Action("delete", false, code.originalText()),
                new PlatformRecordActionAvailability.Action("other", false, "自定义提示", code)));
        try (var language = LanguageContext.use("en")) {
            var output = mapper.valueToTree(advice(Map.of(code, "Platform-managed applications cannot be edited", config, "Configured restriction")).project(input));
            assertThat(output.at("/recordId").asText()).isEqualTo("record");
            assertThat(output.at("/actions/0/available").asBoolean()).isFalse();
            assertThat(output.at("/actions/0/reason").asText()).isEqualTo(code.originalText());
            assertThat(output.at("/actions/0/reasonText/languages/en/text").asText()).isEqualTo("Platform-managed applications cannot be edited");
            assertThat(output.at("/actions/1/reasonText/languages/en/text").asText()).isEqualTo("Configured restriction");
            assertThat(output.at("/actions/2/reasonText").isMissingNode()).isTrue();
            assertThat(output.at("/actions/3/reasonText").isMissingNode()).isTrue();
        }
        assertThat(input.actions().getFirst().reason()).isEqualTo(code.originalText());
    }

    @Test void projectsDeclaredQueryLabelsAndApiTitlesWithoutChangingQueriesOrUnsourcedBusinessTitles() throws Exception {
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("languages", catalog);
        beans.registerSingleton("texts", TextResources.load(getClass().getClassLoader(), catalog));
        var advice = new PlatformPresentationResponseAdvice(mapper, beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class));
        var schema = net.ximatai.muyun.spring.ability.logging.BusinessLogQueryContract.forProfile(
                net.ximatai.muyun.spring.ability.logging.BusinessLogQueryProfile.LOGIN_AUDIT).schema();
        var before = mapper.valueToTree(schema);
        var source = TextReference.configured("config/module/customer/title", "客户名称");
        try (var language = LanguageContext.use("en")) {
            var output = mapper.valueToTree(advice.project(schema));
            assertThat(output.at("/fields/0/title").asText()).isEqualTo("Occurred at");
            assertThat(output.at("/fields/0/persistentControl/title").asText()).isEqualTo("Occurred at");
            assertThat(output.at("/fields/0/titleText/source/originalText").asText()).isEqualTo("发生时间");
            assertThat(output.at("/fields/0/name")).isEqualTo(before.at("/fields/0/name"));
            assertThat(output.at("/fields/0/operators")).isEqualTo(before.at("/fields/0/operators"));
            assertThat(output.at("/fields/0/persistentControl/defaultValues")).isEqualTo(before.at("/fields/0/persistentControl/defaultValues"));
            assertThat(output.get("defaultSorts")).isEqualTo(before.get("defaultSorts"));
            assertThat(output.get("criteriaComposition")).isEqualTo(before.get("criteriaComposition"));
            var declared = new OpenApiModuleCatalogItem("customer", "客户名称", "dynamic", "/customer/openapi", source);
            var literal = new OpenApiModuleCatalogItem("other", "客户名称", "static", "/other/openapi");
            var projected = mapper.valueToTree(advice(Map.of(source, "Customer name")).project(List.of(declared, literal)));
            assertThat(projected.at("/0/title").asText()).isEqualTo("Customer name");
            assertThat(projected.at("/0/documentPath").asText()).isEqualTo("/customer/openapi");
            assertThat(projected.at("/1/title").asText()).isEqualTo("客户名称");
            assertThat(projected.at("/1/titleText").isMissingNode()).isTrue();
        }
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(schema)).isEqualTo(before);
        var method = PlatformModuleWebController.class.getMethod("openApiCatalog");
        assertThat(advice.supports(new org.springframework.core.MethodParameter(method, -1), org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class)).isTrue();
    }

    @Test void addsFormulaDirectoryResourcesWithoutReplacingOriginalsOrBusinessRules() {
        var function = net.ximatai.muyun.spring.platform.metadata.BusinessRuleFunction.declared(
                "platform.formula.function.TODAY", "TODAY", "日期时间", "获取今天",
                "取得当前日期，适合计算当天生效、截止日期等规则", List.of(), "DATE", "TODAY()");
        var unknown = new net.ximatai.muyun.spring.platform.metadata.BusinessRuleFunction(
                "TODAY", "自定义分类", "客户自定义名称", "客户填写的说明", List.of(), "DATE", "TODAY()");
        var mismatched = new net.ximatai.muyun.spring.platform.metadata.BusinessRuleFunction(
                function.name(), function.category(), "已修改的标题", function.description(), function.parameters(),
                function.returnType(), function.example(), function.textSources());
        var snapshot = new net.ximatai.muyun.spring.platform.metadata.BusinessRuleGovernanceSnapshot(
                "demo.order", "same-baseline", List.of(), List.of(), List.of(), List.of(), List.of(function, unknown, mismatched));
        var beans = new DefaultListableBeanFactory();
        var languages = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        beans.registerSingleton("catalog", languages);
        beans.registerSingleton("resources", TextResources.load(getClass().getClassLoader(), languages));
        var projection = new PlatformPresentationResponseAdvice(mapper, beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class));
        var preview = new net.ximatai.muyun.spring.platform.metadata.BusinessRulePreview(snapshot, "candidate", List.of("rule"), List.of());
        try (var language = LanguageContext.use("en")) {
            var output = mapper.valueToTree(projection.project(preview));
            assertThat(output.at("/snapshot/functions/0/title").asText()).isEqualTo("获取今天");
            assertThat(output.at("/snapshot/functions/0/titleText/languages/en/text").asText()).isEqualTo("Get current date");
            assertThat(output.at("/snapshot/functions/0/titleText/source/sourceVersion").asText()).isEqualTo(TextReference.versionOf("获取今天"));
            assertThat(output.at("/snapshot/functions/1/title").asText()).isEqualTo("客户自定义名称");
            assertThat(output.at("/snapshot/functions/1/titleText").isMissingNode()).isTrue();
            assertThat(output.at("/snapshot/functions/0/textSources").isMissingNode()).isTrue();
            assertThat(output.at("/snapshot/functions/2/title").asText()).isEqualTo("已修改的标题");
            assertThat(output.at("/snapshot/functions/2/titleText").isMissingNode()).isTrue();
            assertThat(output.at("/snapshot/baselineFingerprint").asText()).isEqualTo("same-baseline");
            assertThat(output.at("/proposalFingerprint").asText()).isEqualTo("candidate");
            assertThat(output.at("/executionOrder/0").asText()).isEqualTo("rule");
        }
        assertThat(snapshot.functions().getFirst()).isEqualTo(function);
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
    @Test void sharesDirectoryTitleResourcesAcrossPagedCardsNavigationAndTheModuleCatalog() {
        var source = net.ximatai.muyun.spring.platform.module.ModuleTitleView.configuredSource("sales.order", "订单业务");
        var directory = new net.ximatai.muyun.spring.platform.module.ModuleTitleCatalog(Map.of("sales.order",
                new net.ximatai.muyun.spring.platform.module.ModuleTitleView("sales.order", "订单业务", source)));
        var business = new WorkflowRecordSummary("用户填写的订单名", "订单业务", false, source);
        var card = mapper.convertValue(Map.of("instanceId", "instance-1", "moduleAlias", "sales.order", "recordId", "record-1", "addedByAddSign", true, "nodeTitle", "用户加签原名"), WorkflowWorkbenchCard.class).withBusiness(business);
        var page = new WebPageResponse<>(List.of(card), 12, 2, 5, 3, true, Map.of("modules", directory.originalTitles(), "moduleTitles", directory));
        var advice = advice(Map.of(source, "Orders"));
        try (var language = LanguageContext.use("en")) {
            var output = mapper.valueToTree(advice.project(page));
            assertThat(output.at("/records/0/business/moduleTitle").asText()).isEqualTo("Orders");
            assertThat(output.at("/records/0/business/title").asText()).isEqualTo("用户填写的订单名");
            assertThat(output.at("/records/0/business/readable").asBoolean()).isFalse();
            assertThat(output.at("/records/0/nodeTitle").asText()).isEqualTo("用户加签原名");
            assertThat(output.at("/navigation/moduleTitles/modules/sales.order/title").asText()).isEqualTo("Orders");
            assertThat(output.at("/navigation/moduleTitles/modules/sales.order/titleText/source/sourceId").asText()).isEqualTo(source.sourceId());
            assertThat(output.at("/total").asInt()).isEqualTo(12);
            assertThat(output.at("/pageNum").asInt()).isEqualTo(2);
            assertThat(mapper.valueToTree(advice.project(directory)).at("/modules/sales.order/title").asText()).isEqualTo("Orders");
        }
        assertThat(directory.originalTitles()).containsEntry("sales.order", "订单业务");
        assertThat(card.business()).isSameAs(business);
        assertThat(card.business().moduleTitle()).isEqualTo("订单业务");
    }

    @Test void bindsCheckTitlesAndFailuresToFrozenIdentityAndKeepsUnidentifiedFailuresOriginal() {
        var first = new WorkflowTaskCheckResult(); first.setCheckKey("first"); first.setCheckTitle("Business completion condition");
        first.setFailureMessage("Completion condition not met"); first.setPassed(false); first.setResultPayloadText("{\"count\":0}");
        var second = new WorkflowTaskCheckResult(); second.setCheckKey("second"); second.setCheckTitle("Business completion condition");
        second.setFailureMessage("Completion condition not met"); second.setPassed(false);
        var title1 = ConfigurationTextSources.workflowCheckText("v1", "task", "first", "title", first.getCheckTitle());
        var title2 = ConfigurationTextSources.workflowCheckText("v1", "task", "second", "title", second.getCheckTitle());
        var failure1 = ConfigurationTextSources.workflowCheckText("v1", "task", "first", "failureMessage", first.getFailureMessage());
        var failure2 = ConfigurationTextSources.workflowCheckText("v1", "task", "second", "failureMessage", second.getFailureMessage());
        var newer = ConfigurationTextSources.workflowCheckText("v2", "task", "first", "failureMessage", first.getFailureMessage());
        var changed = ConfigurationTextSources.workflowCheckText("v1", "task", "first", "failureMessage", "Changed original");
        var projection = adviceForTranslations(Map.of("zh-CN", Map.of(title1, "第一项完成条件", title2, "第二项完成条件",
                failure1, "第一项尚未完成", failure2, "第二项尚未完成", newer, "新版本提示", changed, "另一原文提示")));
        var evaluation = WorkflowModuleTaskEvaluation.failedCheck(first, List.of(first, second), List.of());
        var process = new WorkflowModuleTaskProcessBundle("t", "i", "v1", "task", "sales.order", "r", null, null, null, evaluation, null);
        try (var language = LanguageContext.use("zh-CN")) {
            var output = mapper.valueToTree(projection.project(WorkflowModuleTaskContinueResult.blocked(process)));
            assertThat(output.at("/processBundle/evaluation/failureMessage").asText()).isEqualTo("第一项尚未完成");
            assertThat(output.at("/processBundle/evaluation/failureCheckKey").asText()).isEqualTo("first");
            assertThat(output.at("/processBundle/evaluation/checkResults/0/checkTitle").asText()).isEqualTo("第一项完成条件");
            assertThat(output.at("/processBundle/evaluation/checkResults/1/checkTitle").asText()).isEqualTo("第二项完成条件");
            assertThat(output.at("/processBundle/evaluation/checkResults/1/failureMessage").asText()).isEqualTo("第二项尚未完成");
            assertThat(output.at("/processBundle/evaluation/checkResults/0/resultPayloadText").asText()).isEqualTo(first.getResultPayloadText());
            assertThat(output.at("/processBundle/evaluation/checkResults/0/passed").asBoolean()).isFalse();
            assertThat(output.at("/processBundle/evaluation/failureMessageText/source/sourceId").asText()).isEqualTo(failure1.sourceId());
            assertThat(output.at("/processBundle/evaluation/failureMessageText/source/originalText").asText()).isEqualTo(first.getFailureMessage());
            assertThat(output.at("/processBundle/evaluation/failureMessageText/languages/en/text").asText()).isEqualTo(first.getFailureMessage());
            var unidentified = new WorkflowModuleTaskProcessBundle("t", "i", "v1", "task", "sales.order", "r", null, null, null,
                    WorkflowModuleTaskEvaluation.failed("Completion condition not met", List.of(first), List.of()), null);
            var unchanged = mapper.valueToTree(projection.project(unidentified));
            assertThat(unchanged.at("/evaluation/failureMessage").asText()).isEqualTo("Completion condition not met");
            assertThat(unchanged.at("/evaluation/failureMessageText").isMissingNode()).isTrue();
        }
        assertThat(evaluation.failureMessage()).isEqualTo("Completion condition not met");
        assertThat(first.getCheckTitle()).isEqualTo("Business completion condition");
        assertThat(first.getFailureMessage()).isEqualTo("Completion condition not met");
    }

    @Test void deliversFrozenFailureResourcesEvenWhenLiveChecksPassAndActualFailuresAreEmpty() {
        var check = new WorkflowTaskCheckResult(); check.setCheckKey("ready"); check.setPassed(true);
        var declaration = new WorkflowTaskCheckText("ready", "业务完成条件", "条件尚未满足");
        var source = ConfigurationTextSources.workflowCheckText("v1", "task", "ready", "failureMessage", declaration.failureMessage());
        var evaluation = WorkflowModuleTaskEvaluation.passed(List.of(check), List.of()).withCheckTexts(List.of(declaration));
        var process = new WorkflowModuleTaskProcessBundle("t", "i", "v1", "task", "sales.order", "r", null, null, null, evaluation, null);
        try (var language = LanguageContext.use("en")) {
            var output = mapper.valueToTree(advice(Map.of(source, "Condition not yet met")).project(process));
            assertThat(output.at("/evaluation/passed").asBoolean()).isTrue();
            assertThat(output.at("/evaluation/failureMessage").isNull()).isTrue();
            assertThat(output.at("/evaluation/failureMessageText").isMissingNode()).isTrue();
            assertThat(output.at("/evaluation/checkResults/0/failureMessage").isNull()).isTrue();
            assertThat(output.at("/evaluation/checkResults/0/failureMessageText").isMissingNode()).isTrue();
            assertThat(output.at("/evaluation/checkTexts/0/failureMessageText/source/sourceId").asText()).isEqualTo(source.sourceId());
            assertThat(output.at("/evaluation/checkTexts/0/failureMessageText/languages/en/text").asText()).isEqualTo("Condition not yet met");
        }
        assertThat(declaration.failureMessage()).isEqualTo("条件尚未满足");
        assertThat(check.getFailureMessage()).isNull();
    }

    @Test void constructionSourcesCannotOverwriteMismatchedFactsOrBorrowCodeForConfiguration() {
        var original = "模块运行态尚未激活";
        var valid = net.ximatai.muyun.spring.platform.application.ApplicationConstructionText.code("progress.runtime-inactive", original);
        var invalid = new net.ximatai.muyun.spring.platform.application.ApplicationConstructionText(
                new TextReference(TextReference.SourceKind.CODE, valid.source().sourceId(), "sha256-" + "0".repeat(64), original), Map.of());
        var configured = new net.ximatai.muyun.spring.platform.application.ApplicationConstructionText(
                TextReference.configured(valid.source().sourceId(), original), Map.of());
        for (var source : List.of(valid, invalid, configured)) {
            String fact = source == valid ? "用户原始业务文本" : original;
            var progress = new net.ximatai.muyun.spring.platform.application.ApplicationConstructionDeliveryService.Progress(
                    "order", "sample.order", "ACTIVE", true, true, "menu", false, false,
                    List.of(fact), List.of(), List.of(), List.of(source));
            var output = mapper.valueToTree(advice(Map.of(valid.source(), "Runtime inactive")).project(progress));
            assertThat(output.at("/remainingWork/0").asText()).isEqualTo(fact);
            assertThat(output.path("textBindings").isMissingNode()).isTrue();
            assertThat(output.path("businessDataStatus").asText()).isEqualTo("NOT_QUERIED");
        }
    }

}
