package net.ximatai.muyun.spring.common.mutation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RecordSaveMutationMetadataTest {
    @Test
    void addressesAnExistingNestedChildAndKeepsDeletionBoundToItsField() {
        RecordMutationPath path = new RecordMutationPath(List.of(
                new RecordMutationPathNode(null, "order-1"),
                new RecordMutationPathNode("lines", "line-2"),
                new RecordMutationPathNode("drawings", "drawing-3")
        ));

        RecordFileDeletionIntent intent = new RecordFileDeletionIntent(path, "sourceFileId", "file-old");

        assertThat(intent.recordPath().nodes()).extracting(RecordMutationPathNode::relationCode)
                .containsExactly(null, "lines", "drawings");
        assertThat(intent.fieldName()).isEqualTo("sourceFileId");
        assertThat(intent.fileId()).isEqualTo("file-old");
    }

    @Test
    void rejectsAnAmbiguousPath() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordMutationPath(List.of(
                new RecordMutationPathNode("lines", "line-2")
        ))).withMessage("record mutation path root must not declare relationCode");

        assertThatIllegalArgumentException().isThrownBy(() -> new RecordMutationPath(List.of(
                new RecordMutationPathNode(null, "order-1"),
                new RecordMutationPathNode(null, "line-2")
        ))).withMessage("record mutation path child node must declare relationCode");
    }

    @Test
    void normalizesAbsentDeletionMetadataToEmpty() {
        assertThat(new RecordSaveMutationMetadata(null).fileDeletions()).isEmpty();
    }

    @Test
    void restoresTheOuterSaveMetadataAfterANestedScope() {
        RecordSaveMutationMetadata outer = new RecordSaveMutationMetadata(List.of());
        RecordSaveMutationMetadata inner = new RecordSaveMutationMetadata(List.of(
                new RecordFileDeletionIntent(RecordMutationPath.root("order-1"), "fileId", "file-old")
        ));

        try (RecordSaveMutationMetadataContext.Scope ignored = RecordSaveMutationMetadataContext.open(outer)) {
            try (RecordSaveMutationMetadataContext.Scope nested = RecordSaveMutationMetadataContext.open(inner)) {
                assertThat(RecordSaveMutationMetadataContext.current()).contains(inner);
            }
            assertThat(RecordSaveMutationMetadataContext.current()).contains(outer);
        }

        assertThat(RecordSaveMutationMetadataContext.current()).isEmpty();
    }
}
