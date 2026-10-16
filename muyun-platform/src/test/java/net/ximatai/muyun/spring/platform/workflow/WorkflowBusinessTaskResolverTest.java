package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class WorkflowBusinessTaskResolverTest {
    private final DefinitionDao definitions = new DefinitionDao();
    private final CheckDao checks = new CheckDao();
    private final GuideDao guides = new GuideDao();
    private final WorkflowBusinessTaskReferenceValidator references = org.mockito.Mockito.mock(WorkflowBusinessTaskReferenceValidator.class);
    private final WorkflowBusinessTaskResolver resolver = new WorkflowBusinessTaskResolver(definitions, checks, guides,
            new WorkflowConditionService(WorkflowTestSupport.facts()), references);

    @Test
    void publishedNodeKeepsItsCompletionPolicyWhenSharedDefinitionChanges() {
        var definition = definition(false);
        var check = new WorkflowTaskCheck(); check.setId("check-1"); check.setTaskDefinitionId("definition-1");
        check.setEnabled(true); check.setCheckKey("ready"); check.setCheckKind(WorkflowTaskCheckKind.FORMULA);
        check.setExpression("{ready} == true"); check.setFailureMessage("请完成业务准备"); checks.insert(check);
        var guide = new WorkflowTaskGuide(); guide.setId("guide-1"); guide.setTaskDefinitionId("definition-1");
        guide.setEnabled(true); guide.setGuideKey("save"); guide.setGuideKind(WorkflowTaskGuideKind.EXECUTE_ACTION);
        guide.setTargetActionCode("update"); guide.setGuideConfigText("{\"editableFields\":[\"ready\"]}"); guide.setTitle("完成准备"); guides.insert(guide);
        var authored = authored();
        resolver.freeze(authored, "sales.contract");
        var running = new WorkflowNodeInstance(); running.setTaskDefinitionId("definition-1");
        running.setNodeSnapshotText(authored.getNodeConfigText());
        definition.setManualConfirm(true); definition.setEnabled(false);
        check.setExpression("false"); check.setEnabled(false); guide.setTitle("已修改");

        var frozen = resolver.resolve(running);
        assertThat(frozen.definition().getManualConfirm()).isFalse();
        assertThat(frozen.definition().getEnabled()).isTrue();
        assertThat(frozen.checks()).hasSize(1);
        assertThat(frozen.checks().getFirst().getExpression()).isEqualTo("{ready} == true");
        assertThat(frozen.guides()).hasSize(1);
        assertThat(frozen.guides().getFirst().getTitle()).isEqualTo("完成准备");
        var newRunning = new WorkflowNodeInstance(); newRunning.setTaskDefinitionId("definition-1");
        assertThatThrownBy(() -> resolver.resolve(newRunning)).isInstanceOf(PlatformException.class).hasMessageContaining("已停用");
    }

    @Test
    void publishingRejectsAutomaticCompletionWithoutChecksAndMutatingFormulas() {
        definition(false);
        assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).isInstanceOf(PlatformException.class).hasMessageContaining("必须配置检查项");
        var check = new WorkflowTaskCheck(); check.setId("check-1"); check.setTaskDefinitionId("definition-1");
        check.setEnabled(true); check.setCheckKey("ready"); check.setCheckKind(WorkflowTaskCheckKind.FORMULA); check.setExpression("{ready} = true"); checks.insert(check);
        assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).isInstanceOf(PlatformException.class).hasMessageContaining("cannot assign business fields");
    }

    @Test
    void automaticCompletionRequiresAnAutomaticCheckInsteadOfOnlyManualConfirmation() {
        definition(false);
        var check = new WorkflowTaskCheck(); check.setId("manual-only"); check.setTaskDefinitionId("definition-1");
        check.setEnabled(true); check.setCheckKey("manual"); check.setCheckKind(WorkflowTaskCheckKind.MANUAL_CONFIRM); checks.insert(check);
        assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).isInstanceOf(PlatformException.class)
                .hasMessageContaining("检查");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidChecks")
    void publicationRejectsMalformedAndMismatchedTypedCheckConfiguration(WorkflowTaskCheckKind kind, String config, String message) {
        definition(true); var check = check(kind); check.setCheckConfigText(config); checks.insert(check);
        assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).isInstanceOf(PlatformException.class).hasMessageContaining(message);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidChecks() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(null, null, "类型不能为空"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.QUERY_EXISTS, "{", "有效 JSON"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.QUERY_EXISTS, "[]", "JSON 对象"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.QUERY_EXISTS, "{\"checkType\":\"MANUAL\"}", "不匹配"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.RELATED_QUERY_EXISTS, "{\"checkType\":\"QUERY_TEMPLATE\",\"queryTemplateId\":\"template\"}", "不匹配"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.QUERY_EXISTS, "{\"checkType\":\"QUERY_TEMPLATE\"}", "必须指定查询模板"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.QUERY_EXISTS, "{\"checkType\":\"QUERY_TEMPLATE\",\"queryTemplateId\":\"template\",\"expectedCount\":0}", "正整数"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.QUERY_EXISTS, "{\"checkType\":\"QUERY_TEMPLATE\",\"queryTemplateId\":\"template\",\"associationViewCode\":\"related\"}", "其他类型"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.RELATED_QUERY_EXISTS, "{\"checkType\":\"ASSOCIATION_VIEW\"}", "配置无效"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.GENERATED_QUERY_EXISTS, "{\"checkType\":\"GENERATED_RELATION\",\"targetModuleAlias\":\"invalid module\"}", "配置无效"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskCheckKind.MANUAL_CONFIRM, "{\"queryTemplateId\":\"template\"}", "不匹配"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "QUERY_EXISTS,QUERY_TEMPLATE,queryTemplateId,template", "RELATED_QUERY_EXISTS,ASSOCIATION_VIEW,associationViewCode,related",
            "GENERATED_QUERY_EXISTS,GENERATED_RELATION,targetModuleAlias,sales.target"})
    void publicationFreezesAllSupportedTypedChecks(WorkflowTaskCheckKind kind, String type, String key, String value) {
        definition(false); var check = check(kind);
        check.setCheckConfigText("{\"checkType\":\"" + type + "\",\"" + key + "\":\"" + value + "\",\"expectedCount\":2}"); checks.insert(check);
        var node = authored(); resolver.freeze(node, "sales.contract");
        var running = new WorkflowNodeInstance(); running.setNodeSnapshotText(node.getNodeConfigText());
        assertThat(resolver.resolve(running).checks().getFirst().getCheckKind()).isEqualTo(kind);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidGuides")
    void publicationRejectsGuideTypeModuleActionAndPayloadErrors(WorkflowTaskGuideKind kind, String module, String action, String config, String message) {
        definition(true); var guide = guide("guide", "prepare", kind); guide.setTargetModuleAlias(module);
        guide.setTargetActionCode(action); guide.setGuideConfigText(config); guides.insert(guide);
        assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).isInstanceOf(PlatformException.class).hasMessageContaining(message);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidGuides() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(null, null, null, null, "类型不能为空"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskGuideKind.OPEN_LIST, "invalid module", null, null, "配置无效"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskGuideKind.OPEN_FORM, "sales.other", null, "{\"editableFields\":[\"ready\"]}", "当前任务业务模块"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskGuideKind.EXECUTE_ACTION, "sales.other", "recalculate", "{}", "当前任务业务模块"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskGuideKind.EXECUTE_ACTION, null, null, null, "配置无效"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskGuideKind.EXECUTE_ACTION, null, "invalid action", null, "配置无效"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskGuideKind.READ_INSTRUCTION, null, "update", null, "不匹配"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskGuideKind.OPEN_FORM, null, null, null, "可编辑业务字段"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskGuideKind.EXECUTE_ACTION, null, "update", "{}", "可编辑业务字段"),
                org.junit.jupiter.params.provider.Arguments.of(WorkflowTaskGuideKind.EXECUTE_ACTION, null, "recalculate", "{\"payload\":[]}", "JSON 对象"));
    }

    @Test void publicationRequiresUniqueKeysAndCompletionDefinitionOwnedByTheWorkflowModule() {
        var definition = definition(true); definition.setModuleAlias("sales.other");
        assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).hasMessageContaining("当前流程业务模块");
        definition.setModuleAlias("sales.contract");
        guides.insert(guide("guide-1", "same", WorkflowTaskGuideKind.READ_INSTRUCTION));
        guides.insert(guide("guide-2", "same", WorkflowTaskGuideKind.OPEN_LIST));
        assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).hasMessageContaining("指引编码重复");
        guides.deleteById("guide-2");
        var first = check(WorkflowTaskCheckKind.MANUAL_CONFIRM); checks.insert(first);
        var second = check(WorkflowTaskCheckKind.MANUAL_CONFIRM); second.setId("second"); checks.insert(second);
        assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).hasMessageContaining("检查项编码重复");
    }

    @Test void publishedWritableGuidesCarryEditableFieldsAndCustomActionPayload() {
        definition(true); var form = guide("form", "edit", WorkflowTaskGuideKind.OPEN_FORM);
        form.setGuideConfigText("{\"editableFields\":[\"title\"]}"); guides.insert(form);
        var action = guide("action", "recalculate", WorkflowTaskGuideKind.EXECUTE_ACTION); action.setTargetActionCode("recalculate");
        action.setGuideConfigText("{\"payload\":{\"strategy\":\"full\"}}"); guides.insert(action);
        var node = authored(); resolver.freeze(node, "sales.contract");
        var running = new WorkflowNodeInstance(); running.setNodeSnapshotText(node.getNodeConfigText());
        assertThat(resolver.resolve(running).guides()).hasSize(2);
        assertThat(resolver.resolve(running).guides().getLast().getGuideConfigText()).contains("strategy");
    }

    @Test void readonlyGuidesMayNavigateToAnotherBusinessModule() {
        definition(true); var guide = guide("list", "related", WorkflowTaskGuideKind.OPEN_LIST);
        guide.setTargetModuleAlias("sales.other"); guides.insert(guide);
        var node = authored(); resolver.freeze(node, "sales.contract");
        var running = new WorkflowNodeInstance(); running.setNodeSnapshotText(node.getNodeConfigText());
        assertThat(resolver.resolve(running).guides().getFirst().getTargetModuleAlias()).isEqualTo("sales.other");
    }

    @Test void publicationCannotReadAnotherTenantsOrSoftDeletedCompletionDefinition() {
        var definition = definition(true); definition.setTenantId("tenant-b");
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).hasMessageContaining("不存在或已停用");
            definition.setTenantId("tenant-a"); definition.setDeleted(true);
            assertThatThrownBy(() -> resolver.freeze(authored(), "sales.contract")).hasMessageContaining("不存在或已停用");
            definition.setDeleted(false);
            assertThatCode(() -> resolver.freeze(authored(), "sales.contract")).doesNotThrowAnyException();
        }
    }

    @Test void publicationIncludesOnlyActiveChildrenInTheSameTenantPartition() {
        var definition = definition(true); definition.setTenantId("tenant-a");
        var ownCheck = check(WorkflowTaskCheckKind.FORMULA); ownCheck.setTenantId("tenant-a"); ownCheck.setExpression("true"); checks.insert(ownCheck);
        var foreignCheck = check(WorkflowTaskCheckKind.FORMULA); foreignCheck.setId("foreign"); foreignCheck.setTenantId("tenant-b");
        foreignCheck.setCheckKey("foreign"); foreignCheck.setExpression("false"); checks.insert(foreignCheck);
        var deletedCheck = check(WorkflowTaskCheckKind.FORMULA); deletedCheck.setId("deleted"); deletedCheck.setTenantId("tenant-a");
        deletedCheck.setCheckKey("deleted"); deletedCheck.setExpression("false"); deletedCheck.setDeleted(true); checks.insert(deletedCheck);
        var ownGuide = guide("own", "own", WorkflowTaskGuideKind.READ_INSTRUCTION); ownGuide.setTenantId("tenant-a"); guides.insert(ownGuide);
        var foreignGuide = guide("foreign", "foreign", WorkflowTaskGuideKind.READ_INSTRUCTION); foreignGuide.setTenantId("tenant-b"); guides.insert(foreignGuide);
        var deletedGuide = guide("deleted", "deleted", WorkflowTaskGuideKind.READ_INSTRUCTION); deletedGuide.setTenantId("tenant-a"); deletedGuide.setDeleted(true); guides.insert(deletedGuide);
        try (var ignored = TenantContext.use("tenant-a")) {
            var node = authored(); resolver.freeze(node, "sales.contract");
            var running = new WorkflowNodeInstance(); running.setNodeSnapshotText(node.getNodeConfigText());
            var frozen = resolver.resolve(running);
            assertThat(frozen.checks()).extracting(WorkflowTaskCheck::getCheckKey).containsExactly("ready");
            assertThat(frozen.guides()).extracting(WorkflowTaskGuide::getGuideKey).containsExactly("own");
        }
    }

    @Test void inlineAuthoringCannotImportExplicitForeignOwnershipOrDeletedConfiguration() throws Exception {
        var definition = definition(true); definition.setTenantId("tenant-b");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var node = authored(); node.setNodeConfigText(mapper.writeValueAsString(java.util.Map.of("task",
                new WorkflowBusinessTaskSpec(definition, List.of(), List.of()))));
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> resolver.freeze(node, "sales.contract")).hasMessageContaining("当前租户");
            definition.setTenantId(null); definition.setDeleted(true);
            node.setNodeConfigText(mapper.writeValueAsString(java.util.Map.of("task",
                    new WorkflowBusinessTaskSpec(definition, List.of(), List.of()))));
            assertThatThrownBy(() -> resolver.freeze(node, "sales.contract")).hasMessageContaining("已删除");
        }
    }

    @Test void referenceResolutionMustSucceedBeforeAnySpecificationIsFrozen() {
        definition(true);
        var check = check(WorkflowTaskCheckKind.QUERY_EXISTS);
        check.setCheckConfigText("{\"checkType\":\"QUERY_TEMPLATE\",\"queryTemplateId\":\"missing\"}"); checks.insert(check);
        var node = authored();
        org.mockito.Mockito.doThrow(new PlatformException("任务查询模板不存在")).when(references).check("sales.contract", check);
        assertThatThrownBy(() -> resolver.freeze(node, "sales.contract")).hasMessageContaining("查询模板不存在");
        assertThat(node.getNodeConfigText()).isNull();
    }

    private WorkflowTaskCheck check(WorkflowTaskCheckKind kind) {
        var check = new WorkflowTaskCheck(); check.setId("check"); check.setTaskDefinitionId("definition-1");
        check.setEnabled(true); check.setCheckKey("ready"); check.setCheckKind(kind); return check;
    }
    private WorkflowTaskGuide guide(String id, String key, WorkflowTaskGuideKind kind) {
        var guide = new WorkflowTaskGuide(); guide.setId(id); guide.setTaskDefinitionId("definition-1");
        guide.setEnabled(true); guide.setGuideKey(key); guide.setGuideKind(kind); return guide;
    }

    private WorkflowTaskDefinition definition(boolean manual) {
        var definition = new WorkflowTaskDefinition(); definition.setId("definition-1");
        definition.setModuleAlias("sales.contract"); definition.setEnabled(true); definition.setManualConfirm(manual); definitions.insert(definition); return definition;
    }
    private WorkflowNodeDefinition authored() {
        var node = new WorkflowNodeDefinition(); node.setNodeType(WorkflowNodeType.TASK); node.setTaskDefinitionId("definition-1"); return node;
    }
    private static class DefinitionDao extends TestMemoryDao<WorkflowTaskDefinition> implements WorkflowTaskDefinitionDao {}
    private static class CheckDao extends TestMemoryDao<WorkflowTaskCheck> implements WorkflowTaskCheckDao {}
    private static class GuideDao extends TestMemoryDao<WorkflowTaskGuide> implements WorkflowTaskGuideDao {}
}
