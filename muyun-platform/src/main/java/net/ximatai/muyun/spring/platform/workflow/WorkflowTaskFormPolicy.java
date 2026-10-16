package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Published form guides explicitly authorize business fields, never platform ownership or approval state. */
final class WorkflowTaskFormPolicy {
    private static final Set<String> RESERVED = Set.of("id", "version", "tenantId", "createdAt", "createdBy",
            "updatedAt", "updatedBy", "deleted", "deletedAt", "deletedBy", "approvalStatus", "approvalInstanceId",
            "approvalSubmittedBy", "approvalSubmittedAt", "approvalCompletedAt");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private WorkflowTaskFormPolicy() {}

    static Set<String> editableFields(WorkflowTaskGuide guide) {
        try {
            var root = MAPPER.readTree(guide.getGuideConfigText() == null ? "{}" : guide.getGuideConfigText());
            var fields = root.path("editableFields");
            if (!fields.isArray() || fields.isEmpty()) throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-task-form-policy.declare-editable-business-fields-for-the-form-guide",
                    "表单指引必须声明可编辑业务字段"));
            Set<String> result = new LinkedHashSet<>();
            for (var field : fields) {
                String name = field.asText();
                if (!field.isTextual() || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || RESERVED.contains(name))
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-task-form-policy.the-form-guide-includes-a-field-that-cannot-be-edited",
                            "表单指引包含不允许编辑的字段: {value1}"), Map.of("value1", String.valueOf(name)));
                if (!result.add(name)) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-task-form-policy.duplicate-form-guide-field",
                        "表单指引存在重复字段: {value1}"), Map.of("value1", String.valueOf(name)));
            }
            return Set.copyOf(result);
        } catch (PlatformException failure) { throw failure; }
        catch (Exception failure) { throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-task-form-policy.invalid-form-guide-configuration",
                "表单指引配置无效"), Map.of(), failure); }
    }

    static void requireValues(WorkflowTaskGuide guide, Map<String, Object> values) {
        var allowed = editableFields(guide);
        if (values != null && !allowed.containsAll(values.keySet()))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-task-form-policy.the-save-contains-fields-that-this-task-does-not-authorize",
                    "业务保存包含当前任务未授权的字段"));
    }
}
