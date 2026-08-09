package net.ximatai.muyun.spring.common.mutation;

import java.util.List;

/**
 * Stable address of an existing record inside the aggregate submitted by a
 * standard save request.
 */
public record RecordMutationPath(List<RecordMutationPathNode> nodes) {
    public RecordMutationPath {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("record mutation path must contain a root node");
        }
        if (nodes.getFirst().relationCode() != null) {
            throw new IllegalArgumentException("record mutation path root must not declare relationCode");
        }
        for (int index = 1; index < nodes.size(); index++) {
            if (nodes.get(index).relationCode() == null) {
                throw new IllegalArgumentException("record mutation path child node must declare relationCode");
            }
        }
    }

    public static RecordMutationPath root(String recordId) {
        return new RecordMutationPath(List.of(new RecordMutationPathNode(null, recordId)));
    }
}
