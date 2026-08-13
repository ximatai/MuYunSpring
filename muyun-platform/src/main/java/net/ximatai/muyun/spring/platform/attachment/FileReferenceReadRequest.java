package net.ximatai.muyun.spring.platform.attachment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Policy input for a short-lived preview or download target of one declared file-reference value. */
public record FileReferenceReadRequest(
        String moduleAlias,
        String relationCode,
        String fieldName,
        Map<String, Object> draft,
        String fileId,
        FileTransferOperation operation
) {
    public FileReferenceReadRequest {
        moduleAlias = requireText(moduleAlias, "moduleAlias");
        fieldName = requireText(fieldName, "fieldName");
        fileId = requireText(fileId, "fileId");
        if (operation != FileTransferOperation.PREVIEW && operation != FileTransferOperation.DOWNLOAD) {
            throw new IllegalArgumentException("file reference read operation must be PREVIEW or DOWNLOAD");
        }
        draft = draft == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(draft));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
