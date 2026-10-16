package net.ximatai.muyun.spring.platform.workflow;

import java.util.List;

/** Authored semantic graph; rendering layout carries no execution authority. */
public record WorkflowDesignDocument(List<WorkflowNodeDefinition> nodes,
                                     List<WorkflowLinkDefinition> links, String layoutJson) {
    public WorkflowDesignDocument {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        links = links == null ? List.of() : List.copyOf(links);
    }
}
