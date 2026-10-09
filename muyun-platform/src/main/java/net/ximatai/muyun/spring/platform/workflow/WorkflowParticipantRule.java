package net.ximatai.muyun.spring.platform.workflow;

import java.util.List;

public record WorkflowParticipantRule(String type, List<String> ids, String fieldName,
                                      String relation, Integer depth, Boolean headOnly) {
    public WorkflowParticipantRule {
        type = type == null ? "USER" : type.toUpperCase(java.util.Locale.ROOT);
        ids = ids == null ? List.of() : List.copyOf(ids);
        depth = depth == null ? 0 : depth;
    }
}
