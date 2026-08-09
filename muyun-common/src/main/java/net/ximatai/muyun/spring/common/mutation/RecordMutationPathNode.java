package net.ximatai.muyun.spring.common.mutation;

/**
 * A concrete record in a save aggregate. The root node has no relation code;
 * every following node identifies the child relation used to reach it.
 */
public record RecordMutationPathNode(String relationCode, String recordId) {
    public RecordMutationPathNode {
        recordId = required(recordId, "recordId");
        relationCode = normalized(relationCode);
    }

    static String required(String value, String name) {
        String normalized = normalized(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
