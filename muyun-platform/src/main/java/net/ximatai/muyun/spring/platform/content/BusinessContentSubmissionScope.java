package net.ximatai.muyun.spring.platform.content;

import java.util.Optional;

/** A trusted entry adapter may bind a persisted original write to the same live reading boundary. */
public interface BusinessContentSubmissionScope {
    Optional<Submission> captureSubmission(BusinessContentOriginalChanged change);

    record Submission(BusinessContentGenerationScope.Reference scope, String moduleAlias,
                      BusinessContentService.Location location, String field, String originalRevisionId) { }
}
