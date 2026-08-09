package net.ximatai.muyun.spring.common.mutation;

import java.util.Optional;

/** Makes standard save metadata available to lifecycle participants without transport fields on records. */
public final class RecordSaveMutationMetadataContext {
    private static final ThreadLocal<RecordSaveMutationMetadata> CURRENT = new ThreadLocal<>();

    private RecordSaveMutationMetadataContext() {
    }

    public static Optional<RecordSaveMutationMetadata> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Scope open(RecordSaveMutationMetadata metadata) {
        RecordSaveMutationMetadata previous = CURRENT.get();
        CURRENT.set(metadata == null ? RecordSaveMutationMetadata.empty() : metadata);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final RecordSaveMutationMetadata previous;

        private Scope(RecordSaveMutationMetadata previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
