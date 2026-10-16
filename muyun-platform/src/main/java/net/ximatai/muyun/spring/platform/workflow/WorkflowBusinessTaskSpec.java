package net.ximatai.muyun.spring.platform.workflow;

import java.util.List;

/** Frozen completion policy and guides belonging to the published workflow version. */
public record WorkflowBusinessTaskSpec(WorkflowTaskDefinition definition, List<WorkflowTaskCheck> checks,
                                      List<WorkflowTaskGuide> guides) {
    public WorkflowBusinessTaskSpec {
        checks = checks == null ? List.of() : List.copyOf(checks);
        guides = guides == null ? List.of() : List.copyOf(guides);
    }
}
