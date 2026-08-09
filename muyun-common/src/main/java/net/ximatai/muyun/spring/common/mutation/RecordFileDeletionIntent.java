package net.ximatai.muyun.spring.common.mutation;

import java.util.Objects;

/**
 * An explicit request to remove one formerly referenced file after the
 * containing record save has committed. It does not describe the new value:
 * that remains in the standard record payload.
 */
public record RecordFileDeletionIntent(RecordMutationPath recordPath, String fieldName, String fileId) {
    public RecordFileDeletionIntent {
        recordPath = Objects.requireNonNull(recordPath, "recordPath must not be null");
        fieldName = RecordMutationPathNode.required(fieldName, "fieldName");
        fileId = RecordMutationPathNode.required(fileId, "fileId");
    }
}
