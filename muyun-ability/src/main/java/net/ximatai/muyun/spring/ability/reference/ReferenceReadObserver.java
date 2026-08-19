package net.ximatai.muyun.spring.ability.reference;

import java.util.List;

/** Optional, payload-free observation hook for a batched reference projection read. */
@FunctionalInterface
public interface ReferenceReadObserver {
    ReferenceReadObserver NONE = request -> { };

    void onProjection(ProjectionRequest request);

    record ProjectionRequest(ReferenceTarget target,
                             List<String> fields,
                             int idCount,
                             Kind kind,
                             String sourceField,
                             String outputField,
                             int hopIndex) {
        public ProjectionRequest {
            if (target == null) {
                throw new IllegalArgumentException("reference read observation target must not be null");
            }
            fields = fields == null ? List.of() : List.copyOf(fields);
            if (idCount < 0) {
                throw new IllegalArgumentException("reference read observation idCount must not be negative");
            }
            kind = kind == null ? Kind.DIRECT : kind;
        }
    }

    enum Kind {
        DIRECT,
        PATH
    }
}
