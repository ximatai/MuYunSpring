package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.attachment.FileReferenceReadRequest;
import net.ximatai.muyun.spring.platform.attachment.FileTransferOperation;

import java.util.Map;

/** HTTP projection of the business facts needed to authorize a file-reference read. */
public record FileReferenceAccessTicketRequest(String relationCode, String fieldName, Map<String, Object> draft,
                                               String fileId) {
    FileReferenceReadRequest toPolicyRequest(String moduleAlias, FileTransferOperation operation) {
        return new FileReferenceReadRequest(moduleAlias, relationCode, fieldName, draft, fileId, operation);
    }
}
