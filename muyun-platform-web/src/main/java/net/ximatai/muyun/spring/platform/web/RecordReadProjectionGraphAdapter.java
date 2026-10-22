package net.ximatai.muyun.spring.platform.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Internal conversion from a compiled read projection to its planning graph.
 * Business modules declare output fields through the UI definition DSL.
 *
 * @hidden
 */
public final class RecordReadProjectionGraphAdapter {
    private static final String ROOT_NODE_ID = "root";

    private RecordReadProjectionGraphAdapter() {
    }

    public static ProjectionGraph adapt(RecordReadProjection projection) {
        if (projection == null) {
            throw new IllegalArgumentException("record read projection must not be null");
        }
        LinkedHashMap<String, MutableNode> nodes = new LinkedHashMap<>();
        nodes.put(ROOT_NODE_ID, MutableNode.root(projection.moduleAlias()));
        List<ProjectionGraphEdge> edges = new ArrayList<>();

        for (String fieldName : projection.internalReadFields()) {
            String nodeId = mainNodeId(fieldName);
            nodes.computeIfAbsent(nodeId, ignored -> MutableNode.field(projection.moduleAlias(),
                            null, fieldName, null))
                    .markInternalRead();
            edges.add(new ProjectionGraphEdge(ROOT_NODE_ID, nodeId,
                    ProjectionGraphEdgeKind.INTERNAL_READ_FIELD, fieldName));
        }
        for (ViewFieldRef field : projection.outputFields()) {
            String nodeId = field.relationCode() == null
                    ? mainNodeId(field.fieldName())
                    : relationNodeId(field.relationCode(), field.fieldName());
            MutableNode node = nodes.computeIfAbsent(nodeId, ignored -> MutableNode.field(
                    projection.moduleAlias(),
                    field.relationCode(),
                    field.fieldName(),
                    field.fieldId()
            ));
            node.mergeFieldId(field.fieldId());
            node.markResponse();
            ProjectionGraphEdgeKind edgeKind = field.relationCode() == null
                    ? ProjectionGraphEdgeKind.MAIN_OUTPUT_FIELD
                    : ProjectionGraphEdgeKind.RELATION_OUTPUT_FIELD;
            edges.add(new ProjectionGraphEdge(ROOT_NODE_ID, nodeId, edgeKind, path(field)));
        }

        return new ProjectionGraph(
                projection.moduleAlias(),
                projection.viewCode(),
                nodes.values().stream().map(MutableNode::toNode).toList(),
                edges,
                projection.postReadTransforms().stream()
                        .map(RecordReadProjectionGraphAdapter::transform)
                        .toList()
        );
    }

    private static ProjectionGraphTransform transform(String expression) {
        return new ProjectionGraphTransform(expression, RecordReadPostTransform.parse(expression).orElse(null));
    }

    private static String path(ViewFieldRef field) {
        return field.relationCode() == null ? field.fieldName() : field.relationCode() + "." + field.fieldName();
    }

    private static String mainNodeId(String fieldName) {
        return "main:" + fieldName;
    }

    private static String relationNodeId(String relationCode, String fieldName) {
        return "relation:" + relationCode + ":" + fieldName;
    }

    private static final class MutableNode {
        private final String nodeId;
        private final ProjectionGraphNodeKind nodeKind;
        private final String moduleAlias;
        private final String relationCode;
        private final String fieldName;
        private String fieldId;
        private boolean responseField;
        private boolean internalReadField;

        private MutableNode(String nodeId,
                            ProjectionGraphNodeKind nodeKind,
                            String moduleAlias,
                            String relationCode,
                            String fieldName,
                            String fieldId) {
            this.nodeId = nodeId;
            this.nodeKind = nodeKind;
            this.moduleAlias = moduleAlias;
            this.relationCode = relationCode;
            this.fieldName = fieldName;
            this.fieldId = fieldId;
        }

        private static MutableNode root(String moduleAlias) {
            return new MutableNode(ROOT_NODE_ID, ProjectionGraphNodeKind.ROOT, moduleAlias,
                    null, null, null);
        }

        private static MutableNode field(String moduleAlias, String relationCode, String fieldName, String fieldId) {
            return new MutableNode(relationCode == null ? mainNodeId(fieldName) : relationNodeId(relationCode, fieldName),
                    ProjectionGraphNodeKind.FIELD, moduleAlias, relationCode, fieldName, fieldId);
        }

        private void markResponse() {
            responseField = true;
        }

        private void mergeFieldId(String value) {
            if (value != null && !value.isBlank() && fieldId == null) {
                fieldId = value.trim();
            }
        }

        private void markInternalRead() {
            internalReadField = true;
        }

        private ProjectionGraphNode toNode() {
            return new ProjectionGraphNode(nodeId, nodeKind, moduleAlias, null, null, relationCode,
                    fieldName, fieldId, responseField, internalReadField);
        }
    }
}
