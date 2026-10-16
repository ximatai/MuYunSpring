package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

final class WorkflowParticipantPolicyCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> TYPES = Set.of("USER", "ROLE", "DEPT", "ORG", "RELATIVE", "FIELD", "INITIATOR_SELF");
    private WorkflowParticipantPolicyCodec() {}

    static void validate(String text, String nodeKey) {
        var policies = rules(text, nodeKey);
        if (policies.isEmpty()) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-participant-policy-codec.configure-a-participant-source-for-the-node",
                "节点必须声明参与人来源: {value1}"), Map.of("value1", String.valueOf(nodeKey)));
        for (var rule : policies) {
            if (Set.of("USER", "ROLE", "DEPT", "ORG").contains(rule.type()) && rule.ids().isEmpty())
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-participant-policy-codec.select-participant-targets",
                        "参与人目标不能为空: {value1}"), Map.of("value1", String.valueOf(nodeKey)));
            if ("FIELD".equals(rule.type()) && (rule.fieldName() == null || rule.fieldName().isBlank()))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-participant-policy-codec.select-a-business-personnel-field",
                        "业务人员字段不能为空: {value1}"), Map.of("value1", String.valueOf(nodeKey)));
            if ("RELATIVE".equals(rule.type()) && !Set.of("SELF", "SUPERVISOR", "DEPARTMENT", "DEPARTMENT_MANAGER", "ORGANIZATION", "ORGANIZATION_MANAGER").contains(rule.relation() == null ? "" : rule.relation()))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-participant-policy-codec.invalid-relative-participant-relationship",
                        "相对人员关系无效: {value1}"), Map.of("value1", String.valueOf(nodeKey)));
            if (rule.depth() < 0 || rule.depth() > 32) throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-participant-policy-codec.the-participant-relationship-depth-must-be-between-0-and-32",
                    "参与人关系深度必须在 0 至 32 之间"));
        }
    }

    static List<WorkflowParticipantRule> rules(String text, String nodeKey) {
        if (text == null || text.isBlank()) return List.of();
        if (!text.stripLeading().startsWith("{") && !text.stripLeading().startsWith("[")) {
            String raw = text.startsWith("user:") ? text.substring(5) : text;
            if (raw.contains(":")) throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-participant-policy-codec.unsupported-participant-configuration",
                    "unsupported workflow participant policy: {value1}"), Map.of("value1", String.valueOf(nodeKey)));
            return List.of(new WorkflowParticipantRule("USER", List.of(raw.split("[,;]")).stream()
                    .map(String::trim).filter(item -> !item.isEmpty()).distinct().toList(), null, null, null, null));
        }
        try {
            var root = MAPPER.readTree(text);
            var entries = root.isArray() ? root : root.path("rules").isArray() ? root.path("rules") : null;
            List<WorkflowParticipantRule> result = new ArrayList<>();
            if (entries == null) result.add(rule(root)); else entries.forEach(item -> result.add(rule(item)));
            return List.copyOf(result);
        } catch (PlatformException failure) { throw failure; }
        catch (Exception failure) { throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-participant-policy-codec.invalid-participant-configuration",
                "invalid workflow participant policy: {value1}"), Map.of("value1", String.valueOf(nodeKey)), failure); }
    }

    private static WorkflowParticipantRule rule(JsonNode node) {
        if (node.isTextual()) return new WorkflowParticipantRule("USER", List.of(node.asText()), null, null, null, null);
        String type = node.path("type").asText("USER").toUpperCase(Locale.ROOT);
        type = switch (type) { case "DEPARTMENT" -> "DEPT"; case "ORGANIZATION" -> "ORG"; default -> type; };
        if (!TYPES.contains(type)) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-participant-policy-codec.unsupported-participant-type",
                "unsupported workflow participant type: {value1}"), Map.of("value1", String.valueOf(type)));
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String key : List.of("ids", "userIds", "targetIds")) {
            if (node.path(key).isArray()) node.path(key).forEach(id -> { if (!id.asText().isBlank()) ids.add(id.asText()); });
        }
        for (String key : List.of("userId", "targetId", "id", "value")) {
            if (node.path(key).isTextual() && !node.path(key).asText().isBlank()) ids.add(node.path(key).asText());
        }
        return new WorkflowParticipantRule(type, List.copyOf(ids), node.path("fieldName").asText(null),
                node.path("relation").asText(null), node.path("depth").asInt(0), node.path("headOnly").asBoolean(false));
    }

}
