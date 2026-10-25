package net.ximatai.muyun.spring.common.identity;

import java.util.Objects;
import java.util.Optional;

/** Correlation facts for runtime audit, without granting authority or carrying business input. */
public final class JobExecutionContext {
    private static final ThreadLocal<Reference> CURRENT = new ThreadLocal<>();

    private JobExecutionContext() {
    }

    public record Reference(String jobType, String planId, String runId, String identityMode) {
        public Reference {
            jobType = requireText(jobType, "jobType");
            runId = requireText(runId, "runId");
            identityMode = requireText(identityMode, "identityMode");
            planId = planId == null || planId.isBlank() ? null : planId;
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }

    public static Optional<Reference> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Scope use(Reference reference) {
        Reference previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(reference, "reference must not be null"));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
