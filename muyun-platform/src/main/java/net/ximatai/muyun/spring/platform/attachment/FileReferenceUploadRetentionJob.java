package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;

/** Upload admissions use the existing bounded platform job execution and transaction mechanism. */
@Component
public final class FileReferenceUploadRetentionJob implements JobHandler {
    private final FileReferenceUploadService uploads;
    public FileReferenceUploadRetentionJob(FileReferenceUploadService uploads) { this.uploads = uploads; }
    @Override public JobDefinition definition() {
        return new JobDefinition("platform.file-reference-upload-retention", "platform.job.type.fileReferenceUploadRetention",
                JobScope.PLATFORM, JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ofMinutes(1)), "default", null,
                Duration.ofMinutes(5), 1, Duration.ZERO, input -> "retention", true, 3, true, null);
    }
    @Override public JobResult execute(JobContext context) {
        int removed = 0;
        for (int batch = 0; batch < 10; batch++) {
            context.checkCancelled();
            int count = context.transaction(uploads::purgeExpiredBatch);
            removed += count;
            if (count < 200) break;
        }
        return removed == 2000 ? JobResult.partial("BATCH_LIMIT_REACHED", Map.of("removed", (long) removed))
                : JobResult.success("COMPLETE", Map.of("removed", (long) removed));
    }
}
