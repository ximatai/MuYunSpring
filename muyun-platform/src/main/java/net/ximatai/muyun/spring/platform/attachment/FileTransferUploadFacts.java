package net.ximatai.muyun.spring.platform.attachment;

/** Upload identity returned by the trusted file service, never by the browser. */
public record FileTransferUploadFacts(FileTransferFileMetadata metadata, String uploaderUserId, String tenantId) { }
