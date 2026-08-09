package net.ximatai.muyun.spring.common.mutation;

import java.util.List;

/**
 * Metadata about a standard record save, kept outside the business record.
 */
public record RecordSaveMutationMetadata(List<RecordFileDeletionIntent> fileDeletions) {
    public RecordSaveMutationMetadata {
        fileDeletions = fileDeletions == null ? List.of() : List.copyOf(fileDeletions);
    }

    public static RecordSaveMutationMetadata empty() {
        return new RecordSaveMutationMetadata(List.of());
    }
}
