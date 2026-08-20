package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryGroupOperator;

import java.util.List;

/** Immutable published query-template tree; request handling only binds external values. */
public record ModuleQueryTemplatePlan(String templateId, List<Node> nodes) {
    public ModuleQueryTemplatePlan {
        if (templateId == null || templateId.isBlank()) throw new IllegalArgumentException("query template id must not be blank");
        templateId = templateId.trim();
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public record Node(PlatformQueryGroupOperator groupOperator, String fieldName, DynamicQueryOperator operator,
                       String defaultValue, String externalValueKey, String timeZone, List<Node> children) {
        public Node {
            groupOperator = groupOperator == null ? PlatformQueryGroupOperator.AND : groupOperator;
            fieldName = fieldName == null || fieldName.isBlank() ? null : fieldName.trim();
            children = children == null ? List.of() : List.copyOf(children);
            if (fieldName != null && operator == null) throw new IllegalArgumentException("query template leaf requires operator");
        }
        public boolean group() { return fieldName == null; }
    }
}
