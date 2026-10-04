package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.platform.exchange.importer.ImportEntityExecutionSummary;
import java.util.Map;

public record DynamicImportUploadResult(
        int created,
        int updated,
        int skipped,
        int errorCount,
        boolean partialSuccess,
        String message,
        String errorFileName,
        String errorFileToken,
        Map<String, ImportEntityExecutionSummary> summaries
) {
}
