package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowParticipantService {
    private final List<WorkflowIdentityResolver> identities;
    private final ModuleRecordFacts facts;

    public WorkflowParticipantService(List<WorkflowIdentityResolver> identities, ModuleRecordFacts facts) {
        this.identities = List.copyOf(identities);
        this.facts = facts;
    }

    public List<String> resolve(WorkflowInstance instance, WorkflowNodeInstance node) {
        var rules = WorkflowParticipantPolicyCodec.rules(node.getParticipantPolicyText(), node.getNodeKey());
        List<String> result = new ArrayList<>();
        for (var rule : rules) {
            switch (rule.type()) {
                case "USER" -> result.addAll(rule.ids());
                case "INITIATOR_SELF" -> result.add(instance.getStartedBy());
                case "FIELD" -> {
                    if (rule.fieldName() == null || rule.fieldName().isBlank()) throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-participant.select-a-participant-business-field",
                            "参与人业务字段不能为空"));
                    Object value = facts.read(instance.getModuleAlias(), instance.getRecordId()).get(rule.fieldName());
                    if (value instanceof Collection<?> users) users.forEach(user -> result.add(String.valueOf(user)));
                    else if (value != null) result.add(String.valueOf(value));
                }
                default -> {
                    if (identities.isEmpty()) throw new PlatformException("workflow identity adapter is required: " + rule.type());
                    String origin = instance.getStartedBy();
                    if (rule.fieldName() != null) {
                        Object value = facts.read(instance.getModuleAlias(), instance.getRecordId()).get(rule.fieldName());
                        origin = value == null ? null : String.valueOf(value);
                    }
                    for (var adapter : identities) result.addAll(adapter.resolve(rule, origin, instance.getAuthOrgId()));
                }
            }
        }
        var users = result.stream().filter(java.util.Objects::nonNull).filter(user -> !user.isBlank()).distinct()
                .filter(user -> identities.stream().allMatch(adapter -> adapter.isEnabledUser(user))).toList();
        if (users.isEmpty()) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-participant.no-eligible-participants-were-found-for-this-node",
                "节点未解析到有效参与人: {value1}"), Map.of("value1", String.valueOf(node.getNodeTitle())));
        return users;
    }
}
