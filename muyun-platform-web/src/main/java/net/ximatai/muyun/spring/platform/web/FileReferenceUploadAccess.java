package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.attachment.FileTransferOperation;
import java.time.Instant;

/** Browser direct upload access plus the opaque platform admission to confirm afterwards. */
public record FileReferenceUploadAccess(FileTransferOperation operation, String fileId, String accessToken,
                                        String url, Instant expiresAt, String registrationId) { }
