package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadFile;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadIntent;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadRequest;

import java.util.Map;

/** HTTP projection deliberately limited to the policy's explicit input facts. */
public record FileReferenceUploadTicketRequest(String relationCode,
                                               String fieldName,
                                               Map<String, Object> draft,
                                               FileReferenceUploadFile file,
                                               FileReferenceUploadIntent intent) {
    FileReferenceUploadRequest toPolicyRequest(String moduleAlias) {
        return new FileReferenceUploadRequest(moduleAlias, relationCode, fieldName, draft, file, intent);
    }
}
